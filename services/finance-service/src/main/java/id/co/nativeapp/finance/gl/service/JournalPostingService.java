package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.domain.PostingTemplate;
import id.co.nativeapp.finance.gl.domain.TemplateLine;
import id.co.nativeapp.finance.gl.domain.UnbalancedJournalException;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds a balanced {@link JournalEntry} from an {@link EventKind} + gross {@link Money} by
 * resolving the effective {@link PostingTemplate} and its {@link AccountRole} mappings — the
 * "resolve on write" (CQRS) seam between the event consumers and the double-entry GL.
 *
 * <p><strong>SME seam.</strong> The posting rules ({@code posting_template} + {@code
 * role_account_map}) are global-reference DATA, not Java code. An accountant can swap to real COA
 * mappings by inserting higher-version rows; no code change is required. The V13 seeds are all
 * flagged {@code uses_illustrative=true} and carry a loud header comment.
 *
 * <p><strong>Suspense fall-back.</strong> If no template is found for the event kind, or if a role
 * resolves to no account, the service falls back to a two-line suspense entry (Dr SUSPENSE / Cr
 * SUSPENSE for zero net — still balanced but carries the gross on the debit and a matching credit
 * to the same suspense account) with {@code uses_illustrative_rules=true} and logs a warning. Money
 * is never silently dropped (HR-3).
 *
 * <p>Not {@code @Transactional} itself — the caller (the writer bean) owns the transaction
 * boundary. This service assembles the entry in memory; the repository save in the writer persists
 * it inside the writer's {@code @Transactional}.
 */
@Service
public class JournalPostingService {

  private static final Logger log = LoggerFactory.getLogger(JournalPostingService.class);

  private final PostingTemplateResolver templateResolver;
  private final RoleAccountResolver roleResolver;

  public JournalPostingService(
      PostingTemplateResolver templateResolver, RoleAccountResolver roleResolver) {
    this.templateResolver = Objects.requireNonNull(templateResolver, "templateResolver");
    this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
  }

  /**
   * Builds a balanced {@link JournalEntry} for the given event. The entry is NOT yet persisted —
   * the caller must {@code journalEntryRepository.saveAndFlush(entry)} inside a
   * {@code @Transactional} unit of work, then save each line. The caller must also {@code
   * entry.setCompanyId(companyId)} before saving.
   *
   * <p>The entry id is pre-allocated here and shared with the constructed {@link JournalLine}s, so
   * the FK {@code journal_line.entry_id → journal_entry.id} is consistent before any persistence
   * happens. The writer uses {@code saveAndFlush} on the entry to force it to the DB before saving
   * the lines.
   *
   * @param eventKind the event type (drives template resolution)
   * @param period the accounting period {@code YYYY-MM} to stamp on the entry — the caller's
   *     AUTHORITATIVE period (e.g. a payroll run's {@code event.period()}, which is NOT necessarily
   *     {@code periodOf(occurredAt)}). Passing it explicitly keeps the GL entry in the same period
   *     as the dimensional ledger posting for the same event.
   * @param gross the gross monetary amount from the event (never a float)
   * @param occurredAt the event's occurred-at instant (drives effective-dated resolution + the
   *     entry's {@code occurred_at}); distinct from {@code period} (see above)
   * @param sourceEventId the consumed event UUID (idempotency key; will be UNIQUE in the DB)
   * @param description a human-readable description for the journal header
   * @param eventUsesIllustrative the event's own illustrative-rules flag (e.g. a payroll run built
   *     from placeholder statutory data); OR'd with the resolved template's flag so an entry is
   *     marked illustrative if EITHER the rule data OR the source figure is illustrative
   * @return a balanced {@link JournalEntry} ready to be saved
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildEntry(
      EventKind eventKind,
      String period,
      Money gross,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      boolean eventUsesIllustrative) {
    return buildEntry(
        eventKind,
        period,
        gross,
        occurredAt,
        sourceEventId,
        description,
        eventUsesIllustrative,
        null);
  }

  /**
   * Builds a balanced {@link JournalEntry} for the given event, with an optional {@code
   * clearingRoleOverride} (ADR 0006 slice 2 — tender routing). When non-null, any template line
   * whose {@link AccountRole} is {@link AccountRole#CASH_CLEARING} is resolved using {@code
   * clearingRoleOverride} instead — allowing QRIS/card sales to post to the correct settlement
   * clearing account without a new template or a new EventKind. All other parameters are identical
   * to {@link #buildEntry(EventKind, String, Money, Instant, UUID, String, boolean)}.
   *
   * @param clearingRoleOverride when non-null, replaces {@code CASH_CLEARING} in every template
   *     line with this role before resolution; when null, behaviour is identical to the 7-arg form
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildEntry(
      EventKind eventKind,
      String period,
      Money gross,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      boolean eventUsesIllustrative,
      AccountRole clearingRoleOverride) {

    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(gross, "gross");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(description, "description");

    // Pre-allocate the entry id so lines can reference it consistently before persistence.
    UUID entryId = UUID.randomUUID();
    String currency = gross.currency().getCurrencyCode();

    PostingTemplate template = templateResolver.resolve(eventKind, occurredAt);
    if (template == null) {
      log.warn(
          "No posting_template found for eventKind={} occurredAt={}; routing to suspense"
              + " (uses_illustrative=true) — money is NOT dropped, entry will be balanced",
          eventKind,
          occurredAt);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }

    List<JournalLine> lines = new ArrayList<>(template.lines().size());

    for (TemplateLine tl : template.lines()) {
      // ADR 0006 slice 2: when a clearing-role override is supplied and this line uses
      // CASH_CLEARING, substitute the override so QRIS/card sales post to QRIS_CLEARING /
      // CARD_CLEARING rather than the generic CASH_CLEARING. All other roles pass through.
      AccountRole effectiveRole =
          (clearingRoleOverride != null && tl.accountRole() == AccountRole.CASH_CLEARING)
              ? clearingRoleOverride
              : tl.accountRole();

      String accountCode = roleResolver.resolve(effectiveRole, occurredAt);
      if (accountCode == null) {
        log.warn(
            "No role_account_map found for role={} eventKind={} occurredAt={};"
                + " routing line {} to suspense — money NOT dropped",
            effectiveRole,
            eventKind,
            occurredAt,
            tl.lineNo());
        accountCode = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
      }

      Money amount = resolveAmount(tl.amountBasis(), gross);
      JournalLine line =
          switch (tl.side()) {
            case DEBIT -> JournalLine.debit(entryId, tl.lineNo(), accountCode, amount);
            case CREDIT -> JournalLine.credit(entryId, tl.lineNo(), accountCode, amount);
          };
      lines.add(line);
    }

    try {
      return JournalEntry.balanced(
          entryId,
          period,
          occurredAt,
          description,
          currency,
          sourceEventId,
          template.usesIllustrative() || eventUsesIllustrative,
          lines);
    } catch (UnbalancedJournalException e) {
      log.error(
          "Posting template for eventKind={} produced an unbalanced entry (misconfigured seed"
              + " data); falling back to suspense entry — money NOT dropped",
          eventKind,
          e);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }
  }

  /**
   * Phase 2 overload: builds a balanced {@link JournalEntry} for a {@code SaleRecorded} event with
   * breakdown fields (subtotal, discount, service charge, tax). Omits any line whose resolved
   * amount is zero (e.g. a DISCOUNT line when there is no discount — {@code JournalEntry.balanced}
   * rejects zero-sided lines). The entry is NOT persisted — the caller saves it inside a
   * {@code @Transactional}.
   *
   * <p>The posting template v2 (SALE event_kind) carries {@code amount_basis} values of {@code
   * GROSS_REVENUE}, {@code DISCOUNT}, {@code SERVICE_CHARGE}, and {@code TAX} alongside the
   * standard {@code GROSS} (grand-total clearing debit). The resolver selects the correct breakdown
   * field; finance NEVER recomputes the amounts.
   *
   * @param eventKind must be {@link EventKind#SALE}
   * @param period the accounting period {@code YYYY-MM}
   * @param occurredAt the event's occurred-at instant
   * @param sourceEventId the consumed event UUID (idempotency key)
   * @param description human-readable description for the journal header
   * @param event the decoded {@link SaleRecordedEvent} carrying the breakdown; must not be null
   * @param clearingRoleOverride optional clearing-account role override (tender routing)
   * @return a balanced {@link JournalEntry} with only non-zero lines, ready to be saved
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildEntryForSale(
      EventKind eventKind,
      String period,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      SaleRecordedEvent event,
      AccountRole clearingRoleOverride) {

    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(event, "event");

    UUID entryId = UUID.randomUUID();
    String currency = event.amount().currency().getCurrencyCode();
    Money gross = event.amount();

    PostingTemplate template = templateResolver.resolve(eventKind, occurredAt);
    if (template == null) {
      log.warn(
          "No posting_template found for eventKind={} occurredAt={}; routing to suspense",
          eventKind,
          occurredAt);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }

    List<JournalLine> lines = new ArrayList<>(template.lines().size());

    for (TemplateLine tl : template.lines()) {
      AccountRole effectiveRole =
          (clearingRoleOverride != null && tl.accountRole() == AccountRole.CASH_CLEARING)
              ? clearingRoleOverride
              : tl.accountRole();

      // Resolve amount using breakdown-aware resolver.
      Money amount = resolveAmount(tl.amountBasis(), gross, event);

      // Omit any line whose amount is zero (e.g. DISCOUNT when discount=0, TAX when tax=0).
      // JournalEntry.balanced rejects zero-sided lines; we simply skip them here.
      if (amount.isZero()) {
        continue;
      }

      String accountCode = roleResolver.resolve(effectiveRole, occurredAt);
      if (accountCode == null) {
        log.warn(
            "No role_account_map found for role={} eventKind={} occurredAt={};"
                + " routing line {} to suspense — money NOT dropped",
            effectiveRole,
            eventKind,
            occurredAt,
            tl.lineNo());
        accountCode = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
      }

      JournalLine line =
          switch (tl.side()) {
            case DEBIT -> JournalLine.debit(entryId, tl.lineNo(), accountCode, amount);
            case CREDIT -> JournalLine.credit(entryId, tl.lineNo(), accountCode, amount);
          };
      lines.add(line);
    }

    boolean usesIllustrative = template.usesIllustrative() || event.effectiveUsesIllustrative();

    try {
      return JournalEntry.balanced(
          entryId,
          period,
          occurredAt,
          description,
          currency,
          sourceEventId,
          usesIllustrative,
          lines);
    } catch (UnbalancedJournalException e) {
      log.error(
          "Phase 2 SALE posting template produced an unbalanced entry (misconfigured seed data);"
              + " falling back to suspense — money NOT dropped",
          e);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }
  }

  /**
   * Builds a balanced {@link JournalEntry} from an explicit map of {@code amount_basis → Money},
   * resolving the effective {@link PostingTemplate} for {@code eventKind}. This is the
   * finance-native counterpart of {@link #buildEntryForSale} for postings that do NOT originate
   * from a {@code SaleRecorded} event — the AR invoice-issue / payment / void postings (Phase 1).
   * Each template line's {@code amount_basis} selects an amount from {@code amountsByBasis}; a line
   * whose amount is absent or zero is omitted ({@code JournalEntry.balanced} rejects zero-sided
   * lines), so a non-taxable invoice's TAX line simply drops out. The entry is NOT persisted — the
   * caller saves it inside a {@code @Transactional} unit of work and must call {@code
   * entry.setCompanyId(...)} first.
   *
   * @param eventKind the posting kind (e.g. {@link EventKind#INVOICE_ISSUED})
   * @param period the accounting period {@code YYYY-MM}
   * @param occurredAt the posting instant (drives effective-dated resolution + the entry timestamp)
   * @param sourceEventId the idempotency key (UNIQUE in the DB; e.g. the invoice or payment id)
   * @param description a human-readable description for the journal header
   * @param eventUsesIllustrative OR'd with the resolved template's flag onto the entry
   * @param amountsByBasis amounts keyed by {@code amount_basis} — must contain {@code "GROSS"}
   *     (whose currency is the entry currency); all values must share that currency
   * @return a balanced {@link JournalEntry} with only the non-zero lines, ready to be saved
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildEntryFromBreakdown(
      EventKind eventKind,
      String period,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      boolean eventUsesIllustrative,
      Map<String, Money> amountsByBasis) {

    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(amountsByBasis, "amountsByBasis");

    Money gross = amountsByBasis.get("GROSS");
    if (gross == null) {
      throw new IllegalArgumentException(
          "amountsByBasis must contain a GROSS amount (the entry currency + total)");
    }
    UUID entryId = UUID.randomUUID();
    Currency currency = gross.currency();
    String currencyCode = currency.getCurrencyCode();

    PostingTemplate template = templateResolver.resolve(eventKind, occurredAt);
    if (template == null) {
      log.warn(
          "No posting_template found for eventKind={} occurredAt={}; routing to suspense"
              + " (uses_illustrative=true) — money is NOT dropped, entry will be balanced",
          eventKind,
          occurredAt);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currencyCode, sourceEventId, gross);
    }

    List<JournalLine> lines = new ArrayList<>(template.lines().size());
    for (TemplateLine tl : template.lines()) {
      Money amount =
          amountsByBasis.getOrDefault(
              tl.amountBasis().toUpperCase(Locale.ROOT), Money.zero(currency));
      // Omit any line whose amount is zero (e.g. the TAX line of a non-taxable invoice).
      if (amount.isZero()) {
        continue;
      }
      String accountCode = roleResolver.resolve(tl.accountRole(), occurredAt);
      if (accountCode == null) {
        log.warn(
            "No role_account_map found for role={} eventKind={} occurredAt={};"
                + " routing line {} to suspense — money NOT dropped",
            tl.accountRole(),
            eventKind,
            occurredAt,
            tl.lineNo());
        accountCode = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
      }
      JournalLine line =
          switch (tl.side()) {
            case DEBIT -> JournalLine.debit(entryId, tl.lineNo(), accountCode, amount);
            case CREDIT -> JournalLine.credit(entryId, tl.lineNo(), accountCode, amount);
          };
      lines.add(line);
    }

    try {
      return JournalEntry.balanced(
          entryId,
          period,
          occurredAt,
          description,
          currencyCode,
          sourceEventId,
          template.usesIllustrative() || eventUsesIllustrative,
          lines);
    } catch (UnbalancedJournalException e) {
      log.error(
          "Posting template for eventKind={} produced an unbalanced entry (misconfigured seed"
              + " data); falling back to suspense entry — money NOT dropped",
          eventKind,
          e);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currencyCode, sourceEventId, gross);
    }
  }

  /**
   * Resolves a line amount from the amount basis and a {@link SaleRecordedEvent} breakdown. Extends
   * the original single-arg form to support Phase 2 basis values:
   *
   * <ul>
   *   <li>{@code "GROSS"} — the grand total ({@link SaleRecordedEvent#amount()})
   *   <li>{@code "GROSS_REVENUE"} — the subtotal ({@link SaleRecordedEvent#effectiveSubtotal()})
   *   <li>{@code "DISCOUNT"} — the discount ({@link SaleRecordedEvent#effectiveDiscount()})
   *   <li>{@code "SERVICE_CHARGE"} — the service charge ({@link
   *       SaleRecordedEvent#effectiveServiceCharge()})
   *   <li>{@code "TAX"} — the tax amount ({@link SaleRecordedEvent#effectiveTax()})
   * </ul>
   *
   * and the Phase 4 (ADR 0027, V37 SALE v3) basis values:
   *
   * <ul>
   *   <li>{@code "NET_TENDER"} — the residual clearing leg: {@code amount −
   *       gift_card_redeemed_minor} ({@link SaleRecordedEvent#amount()} minus {@link
   *       SaleRecordedEvent#effectiveGiftCardRedeemed()}). For an event with no gift-card
   *       redemption this equals {@code amount} exactly, so it collapses to the pre-Phase-4 {@code
   *       "GROSS"} clearing-debit value (the legacy-byte-identical guarantee).
   *   <li>{@code "GIFT_CARD_TENDER"} — the gift-card-settled leg ({@link
   *       SaleRecordedEvent#effectiveGiftCardRedeemed()}); zero (and therefore omitted by the
   *       caller's zero-line-omission) when no gift card was used.
   *   <li>{@code "LOYALTY_REDEEMED"} — the points-redemption contra-revenue leg ({@link
   *       SaleRecordedEvent#effectiveLoyaltyRedeemed()}); zero (and therefore omitted) when no
   *       points were redeemed.
   * </ul>
   *
   * <p>Amounts are NEVER recomputed in finance — each basis just SELECTS a field carried on the
   * event (or, for {@code NET_TENDER}, a single subtraction of two such fields). A null {@code
   * event} falls back to the original GROSS-only behaviour.
   */
  static Money resolveAmount(String amountBasis, Money gross, SaleRecordedEvent event) {
    if (event == null) {
      return resolveAmount(amountBasis, gross);
    }
    return switch (amountBasis.toUpperCase()) {
      case "GROSS" -> gross;
      case "GROSS_REVENUE" -> event.effectiveSubtotal();
      case "DISCOUNT" -> event.effectiveDiscount();
      case "SERVICE_CHARGE" -> event.effectiveServiceCharge();
      case "TAX" -> event.effectiveTax();
      case "NET_TENDER" -> event.amount().minus(event.effectiveGiftCardRedeemed());
      case "GIFT_CARD_TENDER" -> event.effectiveGiftCardRedeemed();
      case "LOYALTY_REDEEMED" -> event.effectiveLoyaltyRedeemed();
      default ->
          throw new IllegalStateException(
              "Unsupported amount_basis: '"
                  + amountBasis
                  + "' — supported values: GROSS, GROSS_REVENUE, DISCOUNT, SERVICE_CHARGE, TAX,"
                  + " NET_TENDER, GIFT_CARD_TENDER, LOYALTY_REDEEMED");
    };
  }

  /**
   * Resolves a line amount from the amount basis. Only {@code "GROSS"} is supported for non-SALE
   * events; use {@link #resolveAmount(String, Money, SaleRecordedEvent)} for Phase 2 breakdown.
   */
  private static Money resolveAmount(String amountBasis, Money gross) {
    if ("GROSS".equalsIgnoreCase(amountBasis)) {
      return gross;
    }
    throw new IllegalStateException(
        "Unsupported amount_basis: '"
            + amountBasis
            + "' — only GROSS is implemented for non-SALE events");
  }

  /**
   * Builds a two-line suspense entry (Dr SUSPENSE / Cr SUSPENSE). Both lines use the same suspense
   * account so the entry is trivially balanced (Σdr == Σcr == gross). Used when no template is
   * seeded or a mis-seeded template is rejected by the balanced invariant. Money is never dropped;
   * the suspense balance is an operator signal to fix the seed data.
   *
   * <p>The caller passes the pre-allocated {@code entryId} so the lines' FK is consistent with the
   * entry id that will be saved.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  private JournalEntry buildSuspenseEntry(
      UUID entryId,
      String period,
      Instant occurredAt,
      String description,
      String currency,
      UUID sourceEventId,
      Money gross) {

    String suspense = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, suspense, gross),
            JournalLine.credit(entryId, 2, suspense, gross));

    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        description + " [SUSPENSE — no template]",
        currency,
        sourceEventId,
        true, // always illustrative for a suspense fallback
        lines);
  }
}
