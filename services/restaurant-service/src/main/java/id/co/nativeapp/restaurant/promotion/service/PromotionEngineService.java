package id.co.nativeapp.restaurant.promotion.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.promotion.domain.PromoRuleType;
import id.co.nativeapp.restaurant.promotion.domain.PromoScopeKind;
import id.co.nativeapp.restaurant.promotion.dto.AppliedDeduction;
import id.co.nativeapp.restaurant.promotion.dto.CouponOutcome;
import id.co.nativeapp.restaurant.promotion.dto.EvalInput;
import id.co.nativeapp.restaurant.promotion.dto.EvalLine;
import id.co.nativeapp.restaurant.promotion.dto.EvalResult;
import id.co.nativeapp.restaurant.promotion.projection.CouponRuleView;
import id.co.nativeapp.restaurant.promotion.projection.PromoRuleView;
import id.co.nativeapp.restaurant.promotion.repository.CouponRepository;
import id.co.nativeapp.restaurant.promotion.repository.PromoRuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Collapses every promotion (automatic rules, at most one coupon, the pre-existing manual discount)
 * into ONE {@link Money} figure the caller feeds to {@code TaxChargeService.resolve} as the fixed
 * discount (ADR 0026) — plus the per-rule audit detail ({@link EvalResult#deductions()}) the caller
 * persists as {@code applied_promotion} rows.
 *
 * <p><strong>Pure evaluation — no writes.</strong> This component only READS ({@link
 * PromoRuleRepository}/{@link CouponRepository}); it never redeems a coupon (the atomic {@code
 * redeemIfAvailable} UPDATE is the caller's job, inside its own write transaction, AFTER inspecting
 * {@link EvalResult#couponOutcome()}) and never persists an {@code applied_promotion} row. It has
 * no {@code @Transactional} of its own — it always runs inside the caller's already-open
 * transaction (the {@code OrderWriter}/{@code BillWriter} REQUIRES_NEW method, or {@code
 * OrderReader}'s read-only quote transaction), exactly like {@code TaxChargeService}.
 *
 * <p><strong>Composition order (deterministic, ADR 0026 / V16 migration comment):</strong>
 *
 * <ol>
 *   <li><strong>Line-scope</strong> {@code PERCENT_OFF_LINE} rules, per matching line (scope {@code
 *       ITEM}: {@code menuItemId == scope_ref_id}; scope {@code CATEGORY}: {@code categoryId ==
 *       scope_ref_id}), each deduction computed against — and clamped to — that line's OWN total.
 *       The cart subtotal stays GROSS; line discounts are components of the eventual ORDER
 *       discount, not a subtotal reduction (finance semantics unchanged).
 *   <li><strong>Automatic order-scope</strong> rules ({@code PERCENT_OFF_ORDER}/{@code
 *       AMOUNT_OFF_ORDER}, {@code requires_coupon = FALSE}), in {@code priority ASC} order (ties by
 *       id); each computed against the GROSS subtotal independently (not compounding). A rule with
 *       {@code exclusive = TRUE} stops further automatics from being evaluated AFTER it is added to
 *       the pending list (so higher-priority exclusive rules still run; nothing after them does).
 *       {@code min_subtotal_minor} gates a rule: subtotal must be &ge; the minimum to qualify at
 *       all.
 *   <li><strong>At most ONE coupon</strong>: resolved by its normalized code; validated active, not
 *       expired, {@code redeemed_count < max_redemptions} (an ADVISORY read — the atomic UPDATE at
 *       the caller's commit is the real race-safe guard), and its linked rule active + effective +
 *       within any happy-hour window. A coupon may link ANY rule type, including a {@code
 *       requires_coupon = TRUE} one (that flag exists precisely to gate a rule behind a coupon).
 *   <li><strong>Manual discount LAST</strong> — the pre-existing staff-entered discount. Carries no
 *       {@code ruleId}, so it never becomes an {@code applied_promotion} row.
 *   <li><strong>Clamp</strong>: the deductions above are accumulated in composition order and
 *       clamped so their sum never exceeds the subtotal — the deduction that would push the running
 *       total past the subtotal is truncated to exactly what remains, and everything after it
 *       becomes zero and is dropped.
 * </ol>
 *
 * <p><strong>Happy-hour windows.</strong> A rule's {@code dow_mask}/{@code window_start}/{@code
 * window_end} are evaluated in the rule's OWN {@code tz} (never the server's zone). {@code bit 0 =
 * Monday .. bit 6 = Sunday}. The window is a half-open {@code [window_start, window_end)}; when
 * {@code window_start > window_end} the window WRAPS past midnight (e.g. {@code 22:00–02:00}
 * matches both {@code 23:00} and {@code 01:00}).
 */
@Component
public class PromotionEngineService {

  private static final String MANUAL_DISCOUNT_LABEL = "Manual discount";

  private final PromoRuleRepository promoRuleRepository;
  private final CouponRepository couponRepository;

  public PromotionEngineService(
      PromoRuleRepository promoRuleRepository, CouponRepository couponRepository) {
    this.promoRuleRepository = promoRuleRepository;
    this.couponRepository = couponRepository;
  }

  /**
   * Evaluates every configured promotion against one cart and returns the single collapsed discount
   * plus its per-rule breakdown. Never throws for an invalid/exhausted coupon — that is reported
   * via {@link EvalResult#couponOutcome()} for the caller to act on (a quote reports it;
   * checkout/pay reject it).
   */
  public EvalResult evaluate(EvalInput input) {
    Currency currency = Currency.getInstance(input.currency());
    Money subtotal = input.subtotal();
    Instant occurredAt = input.occurredAt();
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();

    List<RuleSnapshot> activeRules =
        promoRuleRepository.findActiveEffective(asOf).stream().map(RuleSnapshot::from).toList();

    List<AppliedDeduction> pending = new ArrayList<>();

    // 1. Line-scope PERCENT_OFF_LINE rules, per matching line.
    for (RuleSnapshot rule : activeRules) {
      if (rule.type() != PromoRuleType.PERCENT_OFF_LINE) {
        continue;
      }
      if (!passesGates(rule, subtotal, occurredAt)) {
        continue;
      }
      pending.addAll(lineDeductionsFor(rule, input.lines(), currency, null));
    }

    // 2. Automatic order-scope rules (skip requires_coupon), priority ASC; exclusive stops the
    // loop.
    for (RuleSnapshot rule : activeRules) {
      if (rule.type() != PromoRuleType.PERCENT_OFF_ORDER
          && rule.type() != PromoRuleType.AMOUNT_OFF_ORDER) {
        continue;
      }
      if (rule.requiresCoupon()) {
        continue;
      }
      if (!passesGates(rule, subtotal, occurredAt)) {
        continue;
      }
      Money amount = naturalOrderAmount(rule, subtotal, currency);
      if (amount.isPositive()) {
        pending.add(
            new AppliedDeduction(
                rule.id(), null, rule.name(), rule.type(), rule.rateBp(), null, amount));
      }
      if (rule.exclusive()) {
        break;
      }
    }

    // 3. At most one coupon.
    CouponOutcome couponOutcome = null;
    if (input.couponCode() != null && !input.couponCode().isBlank()) {
      couponOutcome =
          applyCoupon(input.couponCode(), subtotal, occurredAt, currency, input.lines(), pending);
    }

    // 4. Manual discount last (no ruleId — never persisted as an applied_promotion row).
    if (input.manualDiscountMinor() > 0) {
      Money manual = Money.ofMinor(input.manualDiscountMinor(), currency);
      pending.add(
          new AppliedDeduction(null, null, MANUAL_DISCOUNT_LABEL, null, null, null, manual));
    }

    // 5. Sequential clamp: Σ <= subtotal; the overflowing deduction is truncated, everything after
    //    it becomes zero and is dropped.
    List<AppliedDeduction> clamped = sequentialClamp(pending, subtotal, currency);
    Money totalDiscount =
        clamped.stream().map(AppliedDeduction::amount).reduce(Money.zero(currency), Money::plus);
    List<AppliedDeduction> ruleDeductions =
        clamped.stream().filter(d -> d.ruleId() != null).toList();

    // If the clamp zeroed the coupon's own deduction out entirely (subtotal exhausted by earlier
    // deductions), the coupon granted nothing after all — downgrade so no redemption is burned.
    CouponOutcome finalOutcome = couponOutcome;
    if (finalOutcome != null && finalOutcome.redeemable()) {
      UUID appliedCouponId = finalOutcome.couponId();
      boolean survivedClamp =
          ruleDeductions.stream().anyMatch(d -> appliedCouponId.equals(d.couponId()));
      if (!survivedClamp) {
        finalOutcome = finalOutcome.withoutRedemption();
      }
    }

    return new EvalResult(totalDiscount, ruleDeductions, finalOutcome);
  }

  // -------------------------------------------------------------------------
  // Coupon resolution
  // -------------------------------------------------------------------------

  private CouponOutcome applyCoupon(
      String rawCode,
      Money subtotal,
      Instant occurredAt,
      Currency currency,
      List<EvalLine> lines,
      List<AppliedDeduction> pending) {
    String code = normalize(rawCode);
    Optional<CouponRuleView> viewOpt = couponRepository.findViewByCode(code);
    if (viewOpt.isEmpty()) {
      return CouponOutcome.invalid(code);
    }
    CouponRuleView v = viewOpt.get();
    if (!v.isCouponActive()) {
      return CouponOutcome.invalid(code, v.getCouponId(), v.getRuleId());
    }
    if (v.getExpiresAt() != null && !v.getExpiresAt().isAfter(occurredAt)) {
      return CouponOutcome.invalid(code, v.getCouponId(), v.getRuleId());
    }
    if (v.getRedeemedCount() >= v.getMaxRedemptions()) {
      return CouponOutcome.exhausted(code, v.getCouponId(), v.getRuleId());
    }
    RuleSnapshot rule = RuleSnapshot.from(v);
    if (!passesGates(rule, subtotal, occurredAt)) {
      // The linked rule is inactive, outside its effective window, or outside its happy-hour window
      // — "requires-coupon-mismatch" in CouponInvalidException's documented reason set.
      return CouponOutcome.invalid(code, v.getCouponId(), v.getRuleId());
    }

    // A coupon linking a rule that ALREADY fired (automatically or as a line-scope rule) grants
    // nothing new: applying it again would double the deduction. The customer still sees APPLIED —
    // they have the deal — but no second deduction is added and no redemption is burned.
    boolean ruleAlreadyApplied = pending.stream().anyMatch(d -> rule.id().equals(d.ruleId()));
    if (ruleAlreadyApplied) {
      return CouponOutcome.appliedNoNewBenefit(code, v.getCouponId(), v.getRuleId());
    }

    List<AppliedDeduction> added;
    if (rule.type() == PromoRuleType.PERCENT_OFF_LINE) {
      added = lineDeductionsFor(rule, lines, currency, v.getCouponId());
    } else {
      Money amount = naturalOrderAmount(rule, subtotal, currency);
      added =
          amount.isPositive()
              ? List.of(
                  new AppliedDeduction(
                      rule.id(),
                      v.getCouponId(),
                      rule.name(),
                      rule.type(),
                      rule.rateBp(),
                      null,
                      amount))
              : List.of();
    }
    if (added.isEmpty()) {
      // Resolved fine but its natural amount is zero — nothing granted, nothing to burn.
      return CouponOutcome.appliedNoNewBenefit(code, v.getCouponId(), v.getRuleId());
    }
    pending.addAll(added);
    return CouponOutcome.applied(code, v.getCouponId(), v.getRuleId());
  }

  private static String normalize(String rawCode) {
    return rawCode.strip().toUpperCase(Locale.ROOT);
  }

  // -------------------------------------------------------------------------
  // Rule application helpers
  // -------------------------------------------------------------------------

  private static List<AppliedDeduction> lineDeductionsFor(
      RuleSnapshot rule, List<EvalLine> lines, Currency currency, UUID couponId) {
    long rateBp = rule.rateBp() == null ? 0L : rule.rateBp();
    List<AppliedDeduction> result = new ArrayList<>();
    for (EvalLine line : lines) {
      if (!matchesLineScope(rule, line)) {
        continue;
      }
      Money lineTotal = Money.ofMinor(line.lineTotalMinor(), currency);
      Money amount = lineTotal.applyBasisPoints(rateBp).min(lineTotal);
      if (amount.isPositive()) {
        result.add(
            new AppliedDeduction(
                rule.id(),
                couponId,
                rule.name(),
                rule.type(),
                rule.rateBp(),
                line.lineId(),
                amount));
      }
    }
    return result;
  }

  private static boolean matchesLineScope(RuleSnapshot rule, EvalLine line) {
    if (rule.scopeKind() == null || rule.scopeRefId() == null) {
      return false;
    }
    return switch (rule.scopeKind()) {
      case ITEM -> rule.scopeRefId().equals(line.menuItemId());
      case CATEGORY -> rule.scopeRefId().equals(line.categoryId());
    };
  }

  private static Money naturalOrderAmount(RuleSnapshot rule, Money subtotal, Currency currency) {
    return switch (rule.type()) {
      case PERCENT_OFF_ORDER ->
          subtotal.applyBasisPoints(rule.rateBp() == null ? 0L : rule.rateBp());
      case AMOUNT_OFF_ORDER -> {
        if (rule.amountMinor() == null) {
          yield Money.zero(currency);
        }
        if (rule.currency() != null && !rule.currency().equals(currency.getCurrencyCode())) {
          // Never apply a fixed-amount rule denominated in a different currency than the order.
          yield Money.zero(currency);
        }
        yield Money.ofMinor(rule.amountMinor(), currency).min(subtotal);
      }
      case PERCENT_OFF_LINE ->
          throw new IllegalStateException("PERCENT_OFF_LINE has no single order-level amount");
    };
  }

  private static List<AppliedDeduction> sequentialClamp(
      List<AppliedDeduction> pending, Money subtotal, Currency currency) {
    List<AppliedDeduction> result = new ArrayList<>();
    Money used = Money.zero(currency);
    for (AppliedDeduction d : pending) {
      Money remaining = subtotal.minus(used);
      if (!remaining.isPositive()) {
        break;
      }
      Money amount = d.amount().min(remaining);
      if (amount.isPositive()) {
        result.add(d.withAmount(amount));
        used = used.plus(amount);
      }
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Gates: active / effective window / min subtotal / happy-hour window
  // -------------------------------------------------------------------------

  private static boolean passesGates(RuleSnapshot rule, Money subtotal, Instant occurredAt) {
    if (!rule.active()) {
      return false;
    }
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    if (asOf.isBefore(rule.effectiveFrom()) || asOf.isAfter(rule.effectiveTo())) {
      return false;
    }
    if (rule.minSubtotalMinor() != null && subtotal.amountMinor() < rule.minSubtotalMinor()) {
      return false;
    }
    return withinWindow(rule, occurredAt);
  }

  private static boolean withinWindow(RuleSnapshot rule, Instant occurredAt) {
    if (rule.dowMask() == null && rule.windowStart() == null && rule.windowEnd() == null) {
      return true;
    }
    ZoneId zone = ZoneId.of(rule.tz() == null || rule.tz().isBlank() ? "Asia/Jakarta" : rule.tz());
    ZonedDateTime zdt = occurredAt.atZone(zone);

    if (rule.dowMask() != null) {
      int bit = zdt.getDayOfWeek().getValue() - 1; // Monday=1..Sunday=7 -> bit 0..6
      if ((rule.dowMask() & (1 << bit)) == 0) {
        return false;
      }
    }

    LocalTime start = rule.windowStart();
    LocalTime end = rule.windowEnd();
    if (start == null || end == null) {
      // Only one bound set (malformed data) — no time-of-day restriction is enforced.
      return true;
    }
    if (start.equals(end)) {
      // Degenerate zero-width window — never matches.
      return false;
    }
    LocalTime t = zdt.toLocalTime();
    if (start.isBefore(end)) {
      return !t.isBefore(start) && t.isBefore(end);
    }
    // Overnight wrap (e.g. 22:00-02:00): matches [start, midnight) U [midnight, end).
    return !t.isBefore(start) || t.isBefore(end);
  }

  // -------------------------------------------------------------------------
  // Internal rule normalization — unifies PromoRuleView (automatics/line-scope) and CouponRuleView
  // (the coupon-linked rule) behind one shape so the application logic above is written once.
  // -------------------------------------------------------------------------

  private record RuleSnapshot(
      UUID id,
      String name,
      PromoRuleType type,
      PromoScopeKind scopeKind,
      UUID scopeRefId,
      Long rateBp,
      Long amountMinor,
      String currency,
      Long minSubtotalMinor,
      Short dowMask,
      LocalTime windowStart,
      LocalTime windowEnd,
      String tz,
      boolean requiresCoupon,
      boolean exclusive,
      boolean active,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {

    static RuleSnapshot from(PromoRuleView v) {
      return new RuleSnapshot(
          v.getId(),
          v.getName(),
          PromoRuleType.valueOf(v.getRuleType()),
          v.getScopeKind() == null ? null : PromoScopeKind.valueOf(v.getScopeKind()),
          v.getScopeRefId(),
          v.getRateBp(),
          v.getAmountMinor(),
          v.getCurrency(),
          v.getMinSubtotalMinor(),
          v.getDowMask(),
          v.getWindowStart(),
          v.getWindowEnd(),
          v.getTz(),
          v.isRequiresCoupon(),
          v.isExclusive(),
          v.isActive(),
          v.getEffectiveFrom(),
          v.getEffectiveTo());
    }

    static RuleSnapshot from(CouponRuleView v) {
      return new RuleSnapshot(
          v.getRuleId(),
          v.getRuleName(),
          PromoRuleType.valueOf(v.getRuleType()),
          v.getScopeKind() == null ? null : PromoScopeKind.valueOf(v.getScopeKind()),
          v.getScopeRefId(),
          v.getRateBp(),
          v.getAmountMinor(),
          v.getCurrency(),
          v.getMinSubtotalMinor(),
          v.getDowMask(),
          v.getWindowStart(),
          v.getWindowEnd(),
          v.getTz(),
          false, // requiresCoupon is irrelevant once already resolved VIA a coupon
          false, // exclusive is an automatics-loop-only concept; irrelevant for a coupon's own rule
          v.isRuleActive(),
          v.getEffectiveFrom(),
          v.getEffectiveTo());
    }
  }
}
