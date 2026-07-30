package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.carwash.promotion.domain.CouponStatus;
import id.co.nativeapp.carwash.promotion.domain.PromoRuleType;
import id.co.nativeapp.carwash.promotion.domain.PromoScopeKind;
import id.co.nativeapp.carwash.promotion.dto.AppliedDeduction;
import id.co.nativeapp.carwash.promotion.dto.EvalInput;
import id.co.nativeapp.carwash.promotion.dto.EvalLine;
import id.co.nativeapp.carwash.promotion.dto.EvalResult;
import id.co.nativeapp.carwash.promotion.projection.CouponRuleView;
import id.co.nativeapp.carwash.promotion.projection.PromoRuleView;
import id.co.nativeapp.carwash.promotion.repository.CouponRepository;
import id.co.nativeapp.carwash.promotion.repository.PromoRuleRepository;
import id.co.nativeapp.carwash.promotion.service.PromotionEngineService;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link PromotionEngineService} — no Spring context, no database. {@link
 * PromoRuleRepository}/{@link CouponRepository} are Mockito mocks stubbed with {@link FakeRule}/
 * {@link FakeCoupon} fixtures.
 *
 * <p>A THINNER PORT of restaurant-service's {@code PromotionEngineServiceTest} (the engine itself
 * is byte-identical — see {@code PromotionEngineService}'s class javadoc): the deep composition
 * matrix is already proven there. This suite keeps the core cases (composition order, exclusive,
 * min subtotal, requires_coupon, line-scope ITEM matching + clamp, coupon resolution, happy-hour
 * incl. overnight wrap, day-of-week mask, the final clamp) plus ONE carwash-specific case — a
 * CATEGORY-scoped rule never matches (no category dimension in the wash catalog). The 200-iteration
 * randomized property test is intentionally SKIPPED (already proven against the identical engine in
 * restaurant-service).
 */
class PromotionEngineServiceTest {

  private static final Instant NOON =
      Instant.parse("2026-06-15T05:00:00Z"); // 2026-06-15 12:00 Asia/Jakarta (Monday)
  private static final UUID ITEM_A = UUID.randomUUID();
  private static final UUID ITEM_B = UUID.randomUUID();
  private static final UUID CATEGORY_X = UUID.randomUUID();

  private final PromoRuleRepository promoRuleRepository = mock(PromoRuleRepository.class);
  private final CouponRepository couponRepository = mock(CouponRepository.class);
  private final PromotionEngineService engine =
      new PromotionEngineService(promoRuleRepository, couponRepository);

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  private static EvalLine line(UUID itemId, UUID categoryId, long unitPriceMinor, int qty) {
    return new EvalLine(UUID.randomUUID(), itemId, categoryId, unitPriceMinor, qty);
  }

  private void seedRules(FakeRule... rules) {
    List<PromoRuleView> views = new ArrayList<>();
    for (FakeRule r : rules) {
      views.add(r);
    }
    when(promoRuleRepository.findActiveEffective(any())).thenReturn(views);
  }

  private void seedNoCoupon() {
    when(couponRepository.findViewByCode(any())).thenReturn(Optional.empty());
  }

  // -------------------------------------------------------------------------
  // Composition order + priority determinism
  // -------------------------------------------------------------------------

  @Test
  void ordersAutomaticsByPriorityAscendingRegardlessOfInsertionOrder() {
    FakeRule first = FakeRule.percentOffOrder("10% first", 1_000L).priority(10);
    FakeRule second = FakeRule.amountOffOrder("Rp2000 second", 2_000L).priority(20);
    seedRules(first, second); // already priority ASC, as the repository query guarantees
    seedNoCoupon();

    EvalInput input =
        new EvalInput(List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, null, 0L);
    EvalResult result = engine.evaluate(input);

    assertThat(result.deductions()).hasSize(2);
    assertThat(result.deductions().get(0).nameSnapshot()).isEqualTo("10% first");
    assertThat(result.deductions().get(0).amount()).isEqualTo(idr(1_000L));
    assertThat(result.deductions().get(1).nameSnapshot()).isEqualTo("Rp2000 second");
    assertThat(result.deductions().get(1).amount()).isEqualTo(idr(2_000L));
    assertThat(result.totalDiscount()).isEqualTo(idr(3_000L));
  }

  @Test
  void exclusiveRuleStopsFurtherAutomaticsButNotTheCouponOrManualLayer() {
    FakeRule exclusiveFirst =
        FakeRule.percentOffOrder("Exclusive 10%", 1_000L).priority(10).exclusive(true);
    FakeRule neverReached = FakeRule.percentOffOrder("Never reached 5%", 500L).priority(20);
    seedRules(exclusiveFirst, neverReached);
    seedNoCoupon();

    EvalInput input =
        new EvalInput(
            List.of(line(ITEM_A, null, 100_000L, 1)), "IDR", idr(100_000L), NOON, null, 1_000L);
    EvalResult result = engine.evaluate(input);

    assertThat(result.deductions()).hasSize(1);
    assertThat(result.deductions().get(0).nameSnapshot()).isEqualTo("Exclusive 10%");
    assertThat(result.totalDiscount()).isEqualTo(idr(11_000L)); // 10,000 automatic + 1,000 manual
  }

  // -------------------------------------------------------------------------
  // min_subtotal gate
  // -------------------------------------------------------------------------

  @Test
  void minSubtotalGateExcludesARuleBelowTheThreshold() {
    FakeRule needsFifty = FakeRule.percentOffOrder("10% over 50k", 1_000L).minSubtotal(50_000L);
    seedRules(needsFifty);
    seedNoCoupon();

    EvalResult belowThreshold =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 40_000L, 1)), "IDR", idr(40_000L), NOON, null, 0L));
    assertThat(belowThreshold.deductions()).isEmpty();

    EvalResult atThreshold =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 50_000L, 1)), "IDR", idr(50_000L), NOON, null, 0L));
    assertThat(atThreshold.deductions()).hasSize(1);
    assertThat(atThreshold.totalDiscount()).isEqualTo(idr(5_000L));
  }

  // -------------------------------------------------------------------------
  // requires_coupon rules are skipped as automatics
  // -------------------------------------------------------------------------

  @Test
  void requiresCouponRuleIsNeverAppliedAutomatically() {
    FakeRule gated = FakeRule.percentOffOrder("Members only 20%", 2_000L).requiresCoupon(true);
    seedRules(gated);
    seedNoCoupon();

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, null, 0L));

    assertThat(result.deductions()).isEmpty();
    assertThat(result.totalDiscount()).isEqualTo(idr(0L));
  }

  // -------------------------------------------------------------------------
  // Line-scope ITEM matching + per-line clamp; CATEGORY never matches for carwash
  // -------------------------------------------------------------------------

  @Test
  void lineScopeItemRuleOnlyDiscountsTheMatchingLineAndClampsToItsOwnTotal() {
    FakeRule itemRule =
        FakeRule.percentOffLine("50% off item A", 5_000L, PromoScopeKind.ITEM, ITEM_A);
    seedRules(itemRule);
    seedNoCoupon();

    EvalLine lineA = line(ITEM_A, null, 10_000L, 1); // 50% of 10,000 = 5,000
    EvalLine lineB = line(ITEM_B, null, 20_000L, 1); // untouched — different item
    EvalResult result =
        engine.evaluate(new EvalInput(List.of(lineA, lineB), "IDR", idr(30_000L), NOON, null, 0L));

    assertThat(result.deductions()).hasSize(1);
    AppliedDeduction d = result.deductions().get(0);
    assertThat(d.lineRef()).isEqualTo(lineA.lineId());
    assertThat(d.amount()).isEqualTo(idr(5_000L));
  }

  @Test
  void categoryScopedRuleNeverMatchesSinceCarwashLinesCarryNoCategory() {
    // Carwash-specific case (documented in PromoScopeKind/PromotionEngineService): categoryId is
    // ALWAYS null for a carwash EvalLine (no menu_category-equivalent catalog dimension), so a
    // schema-legal CATEGORY-scoped rule can be CREATED but can never MATCH here.
    FakeRule categoryRule =
        FakeRule.percentOffLine("10% off category X", 1_000L, PromoScopeKind.CATEGORY, CATEGORY_X);
    seedRules(categoryRule);
    seedNoCoupon();

    EvalLine lineA = line(ITEM_A, null, 10_000L, 1); // categoryId always null for carwash
    EvalResult result =
        engine.evaluate(new EvalInput(List.of(lineA), "IDR", idr(10_000L), NOON, null, 0L));

    assertThat(result.deductions()).isEmpty();
    assertThat(result.totalDiscount()).isEqualTo(idr(0L));
  }

  @Test
  void lineScopeDeductionNeverExceedsTheLinesOwnTotal() {
    FakeRule fullOff =
        FakeRule.percentOffLine("100% off item A", 10_000L, PromoScopeKind.ITEM, ITEM_A);
    seedRules(fullOff);
    seedNoCoupon();

    EvalLine lineA = line(ITEM_A, null, 1_000L, 1);
    EvalResult result =
        engine.evaluate(new EvalInput(List.of(lineA), "IDR", idr(1_000L), NOON, null, 0L));

    assertThat(result.totalDiscount()).isEqualTo(idr(1_000L));
  }

  // -------------------------------------------------------------------------
  // Coupon resolution
  // -------------------------------------------------------------------------

  @Test
  void couponAppliesItsLinkedRuleAndReportsApplied() {
    UUID ruleId = UUID.randomUUID();
    UUID couponId = UUID.randomUUID();
    seedRules(); // no automatics
    when(couponRepository.findViewByCode("SAVE10"))
        .thenReturn(
            Optional.of(
                FakeCoupon.percentOffOrder(couponId, "SAVE10", ruleId, "Coupon 10% off", 1_000L)));

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)),
                "IDR",
                idr(10_000L),
                NOON,
                " save10 ", // pre-normalization is defensive, not required
                0L));

    assertThat(result.couponOutcome()).isNotNull();
    assertThat(result.couponOutcome().status()).isEqualTo(CouponStatus.APPLIED);
    assertThat(result.couponOutcome().couponId()).isEqualTo(couponId);
    assertThat(result.deductions()).hasSize(1);
    assertThat(result.deductions().get(0).couponId()).isEqualTo(couponId);
    assertThat(result.totalDiscount()).isEqualTo(idr(1_000L));
  }

  @Test
  void unknownCouponCodeReportsInvalidAndAppliesNoDeduction() {
    seedRules();
    when(couponRepository.findViewByCode("UNKNOWN")).thenReturn(Optional.empty());

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, "unknown", 0L));

    assertThat(result.couponOutcome().status()).isEqualTo(CouponStatus.INVALID);
    assertThat(result.deductions()).isEmpty();
  }

  @Test
  void exhaustedCouponReportsExhaustedAdvisoryWithoutTheAtomicUpdate() {
    UUID ruleId = UUID.randomUUID();
    seedRules();
    FakeCoupon exhausted =
        FakeCoupon.percentOffOrder(UUID.randomUUID(), "MAXED", ruleId, "Maxed rule", 1_000L)
            .redeemedCount(3)
            .maxRedemptions(3);
    when(couponRepository.findViewByCode("MAXED")).thenReturn(Optional.of(exhausted));

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, "MAXED", 0L));

    assertThat(result.couponOutcome().status()).isEqualTo(CouponStatus.EXHAUSTED);
    assertThat(result.deductions()).isEmpty();
  }

  @Test
  void aCouponMayLinkARequiresCouponRuleThatAutomaticsWouldHaveSkipped() {
    UUID ruleId = UUID.randomUUID();
    seedRules(); // the gated rule is resolved directly via the coupon join, not the automatics set
    FakeCoupon gated =
        FakeCoupon.percentOffOrder(
            UUID.randomUUID(), "MEMBERS", ruleId, "Members-only 20%", 2_000L);
    when(couponRepository.findViewByCode("MEMBERS")).thenReturn(Optional.of(gated));

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, "MEMBERS", 0L));

    assertThat(result.couponOutcome().status()).isEqualTo(CouponStatus.APPLIED);
    assertThat(result.totalDiscount()).isEqualTo(idr(2_000L));
  }

  // -------------------------------------------------------------------------
  // Happy-hour windows: boundaries, overnight wrap, day-of-week
  // -------------------------------------------------------------------------

  @Test
  void happyHourWindowAppliesOnlyInsideTheHalfOpenRange() {
    FakeRule happyHour =
        FakeRule.percentOffOrder("Lunch 10%", 1_000L)
            .window(LocalTime.of(12, 0), LocalTime.of(14, 0), "Asia/Jakarta");
    seedRules(happyHour);
    seedNoCoupon();

    Instant insideWindow = Instant.parse("2026-06-15T05:00:00Z"); // 12:00 WIB — inclusive start
    Instant justBeforeEnd = Instant.parse("2026-06-15T06:59:00Z"); // 13:59 WIB
    Instant atEndExclusive = Instant.parse("2026-06-15T07:00:00Z"); // 14:00 WIB — exclusive end
    Instant beforeWindow = Instant.parse("2026-06-15T04:59:00Z"); // 11:59 WIB

    assertThat(deductionCount(happyHour, insideWindow)).isEqualTo(1);
    assertThat(deductionCount(happyHour, justBeforeEnd)).isEqualTo(1);
    assertThat(deductionCount(happyHour, atEndExclusive)).isZero();
    assertThat(deductionCount(happyHour, beforeWindow)).isZero();
  }

  @Test
  void overnightWindowWrapsPastMidnight() {
    FakeRule lateNight =
        FakeRule.percentOffOrder("Late-night 10%", 1_000L)
            .window(LocalTime.of(22, 0), LocalTime.of(2, 0), "Asia/Jakarta");
    seedRules(lateNight);
    seedNoCoupon();

    Instant elevenPm = Instant.parse("2026-06-15T16:00:00Z"); // 23:00 WIB
    Instant oneAm = Instant.parse("2026-06-15T18:00:00Z"); // 01:00 WIB
    Instant noonNotInWindow = NOON;

    assertThat(deductionCount(lateNight, elevenPm)).isEqualTo(1);
    assertThat(deductionCount(lateNight, oneAm)).isEqualTo(1);
    assertThat(deductionCount(lateNight, noonNotInWindow)).isZero();
  }

  @Test
  void dayOfWeekMaskGatesTheRuleToTheConfiguredDays() {
    // 2026-06-15 is a Monday. bit 0 = Monday. A mask covering only Sunday (bit 6) must exclude it.
    short sundayOnly = (short) (1 << 6);
    FakeRule sundaysOnly = FakeRule.percentOffOrder("Sunday special", 1_000L).dowMask(sundayOnly);
    seedRules(sundaysOnly);
    seedNoCoupon();

    assertThat(deductionCount(sundaysOnly, NOON)).isZero();

    short mondayOnly = (short) 1;
    FakeRule mondaysOnly = FakeRule.percentOffOrder("Monday special", 1_000L).dowMask(mondayOnly);
    seedRules(mondaysOnly);
    assertThat(deductionCount(mondaysOnly, NOON)).isEqualTo(1);
  }

  private int deductionCount(FakeRule rule, Instant occurredAt) {
    seedRules(rule);
    EvalResult r =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)),
                "IDR",
                idr(10_000L),
                occurredAt,
                null,
                0L));
    return r.deductions().size();
  }

  // -------------------------------------------------------------------------
  // Final clamp + zero-amount dropping
  // -------------------------------------------------------------------------

  @Test
  void theFinalClampNeverLetsTotalDiscountExceedTheSubtotalAndDropsZeroAmountDeductions() {
    FakeRule big1 = FakeRule.percentOffOrder("60% off", 6_000L).priority(10);
    FakeRule big2 = FakeRule.percentOffOrder("60% off again", 6_000L).priority(20);
    seedRules(big1, big2);
    seedNoCoupon();

    EvalResult result =
        engine.evaluate(
            new EvalInput(
                List.of(line(ITEM_A, null, 10_000L, 1)), "IDR", idr(10_000L), NOON, null, 5_000L));

    // 6,000 (first) + 4,000 (second, clamped) + 0 (manual, dropped) = 10,000 == subtotal.
    assertThat(result.totalDiscount()).isEqualTo(idr(10_000L));
    assertThat(result.totalDiscount().amountMinor())
        .isLessThanOrEqualTo(idr(10_000L).amountMinor());
    assertThat(result.deductions()).hasSize(2);
  }

  // -------------------------------------------------------------------------
  // Test fixtures
  // -------------------------------------------------------------------------

  /** A mutable, fluent {@link PromoRuleView} test fixture. */
  private static final class FakeRule implements PromoRuleView {
    private final UUID id = UUID.randomUUID();
    private String name;
    private PromoRuleType type;
    private PromoScopeKind scopeKind;
    private UUID scopeRefId;
    private Long rateBp;
    private Long amountMinor;
    private String currency;
    private Long minSubtotalMinor;
    private Short dowMask;
    private LocalTime windowStart;
    private LocalTime windowEnd;
    private String tz = "Asia/Jakarta";
    private int priority = 100;
    private boolean exclusive;
    private boolean requiresCoupon;
    private boolean active = true;
    private LocalDate effectiveFrom = LocalDate.of(2020, 1, 1);
    private LocalDate effectiveTo = LocalDate.of(9999, 12, 31);

    static FakeRule percentOffOrder(String name, long rateBp) {
      FakeRule r = new FakeRule();
      r.name = name;
      r.type = PromoRuleType.PERCENT_OFF_ORDER;
      r.rateBp = rateBp;
      return r;
    }

    static FakeRule amountOffOrder(String name, long amountMinor) {
      FakeRule r = new FakeRule();
      r.name = name;
      r.type = PromoRuleType.AMOUNT_OFF_ORDER;
      r.amountMinor = amountMinor;
      r.currency = "IDR";
      return r;
    }

    static FakeRule percentOffLine(
        String name, long rateBp, PromoScopeKind scopeKind, UUID scopeRefId) {
      FakeRule r = new FakeRule();
      r.name = name;
      r.type = PromoRuleType.PERCENT_OFF_LINE;
      r.rateBp = rateBp;
      r.scopeKind = scopeKind;
      r.scopeRefId = scopeRefId;
      return r;
    }

    FakeRule priority(int p) {
      this.priority = p;
      return this;
    }

    FakeRule exclusive(boolean e) {
      this.exclusive = e;
      return this;
    }

    FakeRule requiresCoupon(boolean r) {
      this.requiresCoupon = r;
      return this;
    }

    FakeRule minSubtotal(long minSubtotalMinor) {
      this.minSubtotalMinor = minSubtotalMinor;
      return this;
    }

    FakeRule dowMask(short mask) {
      this.dowMask = mask;
      return this;
    }

    FakeRule window(LocalTime start, LocalTime end, String tz) {
      this.windowStart = start;
      this.windowEnd = end;
      this.tz = tz;
      return this;
    }

    @Override
    public UUID getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getRuleType() {
      return type.name();
    }

    @Override
    public String getScopeKind() {
      return scopeKind == null ? null : scopeKind.name();
    }

    @Override
    public UUID getScopeRefId() {
      return scopeRefId;
    }

    @Override
    public Long getRateBp() {
      return rateBp;
    }

    @Override
    public Long getAmountMinor() {
      return amountMinor;
    }

    @Override
    public String getCurrency() {
      return currency;
    }

    @Override
    public Long getMinSubtotalMinor() {
      return minSubtotalMinor;
    }

    @Override
    public Short getDowMask() {
      return dowMask;
    }

    @Override
    public LocalTime getWindowStart() {
      return windowStart;
    }

    @Override
    public LocalTime getWindowEnd() {
      return windowEnd;
    }

    @Override
    public String getTz() {
      return tz;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public boolean isExclusive() {
      return exclusive;
    }

    @Override
    public boolean isRequiresCoupon() {
      return requiresCoupon;
    }

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public LocalDate getEffectiveFrom() {
      return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
      return effectiveTo;
    }
  }

  /**
   * A mutable, fluent {@link CouponRuleView} test fixture (a coupon linked to a PERCENT_OFF_ORDER
   * rule).
   */
  private static final class FakeCoupon implements CouponRuleView {
    private UUID couponId;
    private String code;
    private int maxRedemptions = 1;
    private int redeemedCount = 0;
    private Instant expiresAt;
    private boolean couponActive = true;
    private UUID ruleId;
    private String ruleName;
    private long rateBp;
    private boolean ruleActive = true;
    private LocalDate effectiveFrom = LocalDate.of(2020, 1, 1);
    private LocalDate effectiveTo = LocalDate.of(9999, 12, 31);

    static FakeCoupon percentOffOrder(
        UUID couponId, String code, UUID ruleId, String ruleName, long rateBp) {
      FakeCoupon c = new FakeCoupon();
      c.couponId = couponId;
      c.code = code;
      c.ruleId = ruleId;
      c.ruleName = ruleName;
      c.rateBp = rateBp;
      return c;
    }

    FakeCoupon redeemedCount(int n) {
      this.redeemedCount = n;
      return this;
    }

    FakeCoupon maxRedemptions(int n) {
      this.maxRedemptions = n;
      return this;
    }

    @Override
    public UUID getCouponId() {
      return couponId;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public int getMaxRedemptions() {
      return maxRedemptions;
    }

    @Override
    public int getRedeemedCount() {
      return redeemedCount;
    }

    @Override
    public Instant getExpiresAt() {
      return expiresAt;
    }

    @Override
    public boolean isCouponActive() {
      return couponActive;
    }

    @Override
    public UUID getRuleId() {
      return ruleId;
    }

    @Override
    public String getRuleName() {
      return ruleName;
    }

    @Override
    public String getRuleType() {
      return PromoRuleType.PERCENT_OFF_ORDER.name();
    }

    @Override
    public String getScopeKind() {
      return null;
    }

    @Override
    public UUID getScopeRefId() {
      return null;
    }

    @Override
    public Long getRateBp() {
      return rateBp;
    }

    @Override
    public Long getAmountMinor() {
      return null;
    }

    @Override
    public String getCurrency() {
      return null;
    }

    @Override
    public Long getMinSubtotalMinor() {
      return null;
    }

    @Override
    public Short getDowMask() {
      return null;
    }

    @Override
    public LocalTime getWindowStart() {
      return null;
    }

    @Override
    public LocalTime getWindowEnd() {
      return null;
    }

    @Override
    public String getTz() {
      return "Asia/Jakarta";
    }

    @Override
    public boolean isRuleActive() {
      return ruleActive;
    }

    @Override
    public LocalDate getEffectiveFrom() {
      return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
      return effectiveTo;
    }
  }
}
