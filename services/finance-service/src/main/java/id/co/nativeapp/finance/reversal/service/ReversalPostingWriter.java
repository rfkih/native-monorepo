package id.co.nativeapp.finance.reversal.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalEntrySaleView;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work that post reversal ledger entries for consumed
 * {@code SaleVoided} and {@code SaleRefunded} events (ADR 0006, slice 4).
 *
 * <p>A distinct bean from {@link ReversalPostingService} so each method is invoked through the
 * Spring proxy: the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} both engage.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Both methods claim the event UUID via {@link
 * ProcessedEventStore#processOnce} inside the transaction; only the first delivery runs the
 * handler. The {@code source_event_id UNIQUE} constraint on {@code ledger_posting} + {@code
 * journal_entry} are the database backstops.
 *
 * <p><strong>Phase 2 per-leg reversal (void + full refund).</strong> When the original SALE entry
 * can be looked up via {@code journal_entry.sale_aggregate_id} (set by {@code RevenuePostingWriter}
 * for SALE entries — V18), the reversal negates each component line exactly: debit ↔ credit swap.
 * This reverses GROSS_REVENUE, DISCOUNT, SERVICE_CHARGE_REVENUE, and TAX_PAYABLE legs individually.
 * When no original SALE entry is found (legacy/carwash sales predating Phase 2, or void of a
 * non-POS sale), the writer falls back to the 2-line GROSS template (Phase 1 behaviour).
 *
 * <p><strong>Read-model reversal.</strong> The {@code consolidated_revenue} and {@code
 * consolidated_pnl} read models are unwound by the <em>net revenue</em> (GROSS_REVENUE credit minus
 * SALES_DISCOUNT debit from the original SALE entry), NOT the grand total. The sale path
 * accumulated net revenue; the void/full-refund path must unwind by the same amount so the read
 * model nets to zero. Legacy sales (no original SALE entry found) carry net == gross, preserving
 * Phase 1 behaviour. The illustrative flag from the original SALE entry is OR-propagated onto the
 * reversal read-model write (sticky monotonic rule).
 *
 * <p><strong>Partial refund.</strong> Partial refunds (refundAmount &lt; originalGrandTotal)
 * require proration of each original leg by {@code refundAmount / originalGrandTotal}. Because
 * integer rounding cannot be made exactly balanced in the general case, partial refunds are
 * rejected with {@link PartialRefundNotSupportedException} (HTTP 400) — a clear, documented
 * boundary. Full refunds are handled per-leg exactly like a void.
 */
@Component
public class ReversalPostingWriter {

  private static final Logger log = LoggerFactory.getLogger(ReversalPostingWriter.class);

  private static final String UPSERT_REVENUE_SQL =
      """
      INSERT INTO consolidated_revenue
          (id, period, total_minor, currency,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, period, currency) DO UPDATE SET
          total_minor = consolidated_revenue.total_minor + EXCLUDED.total_minor,
          updated_at  = now(),
          updated_by  = EXCLUDED.updated_by,
          version     = consolidated_revenue.version + 1
      """;

  /**
   * Atomic accumulate/unwind for the {@code outlet_revenue} per-outlet read model. Used by the void
   * and refund reversal paths to subtract (negate) the original net-revenue amount from the outlet
   * accumulator in the SAME transaction as the consolidated_revenue unwind, keeping the outlet
   * slice consistent with the consolidated total. {@code revenue_minor} may go negative during a
   * period close if refunds arrive before all sales in a redelivery burst — that is correct and
   * expected (the V21 migration has no CHECK constraint on {@code revenue_minor}).
   */
  private static final String UPSERT_OUTLET_REVENUE_SQL =
      """
      INSERT INTO outlet_revenue
          (id, business_id, period, revenue_minor, currency,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, business_id, period, currency) DO UPDATE SET
          revenue_minor = outlet_revenue.revenue_minor + EXCLUDED.revenue_minor,
          updated_at    = now(),
          updated_by    = EXCLUDED.updated_by,
          version       = outlet_revenue.version + 1
      """;

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ReversalPostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      JdbcTemplate jdbcTemplate,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
  }

  /**
   * Posts the reversal for a {@code SaleVoided} event — exactly once per {@code void_id}. Posts a
   * contra ledger entry, unwinds the consolidated-revenue + P&amp;L read models with the negated
   * amount, and writes a {@code SALE_VOID} GL journal entry (per-leg unwind when the original SALE
   * entry is found via {@code sale_aggregate_id}; 2-line GROSS fall-back for legacy sales).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped
   */
  @Transactional
  public boolean postVoid(SaleVoidedEvent event) {
    return processedEvents.processOnce(event.voidId(), () -> postVoidReversal(event));
  }

  /**
   * Posts the reversal for a {@code SaleRefunded} event — exactly once per {@code refund_id}. Posts
   * a proportional contra ledger entry for the refund amount, unwinds the read models by the
   * refunded amount, and writes a {@code SALE_REFUND} GL journal entry (2-line GROSS template;
   * per-leg refund reversal deferred until SaleRefunded v2 carries breakdown fields).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped
   */
  @Transactional
  public boolean postRefund(SaleRefundedEvent event) {
    return processedEvents.processOnce(event.refundId(), () -> postRefundReversal(event));
  }

  private void postVoidReversal(SaleVoidedEvent event) {
    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());
    String currencyCode = amount.currency().getCurrencyCode();

    // Look up the original SALE entry FIRST (by sale_aggregate_id, set by RevenuePostingWriter —
    // V18): its tender legs give the true grand total the SALE ledger_posting was booked with,
    // which the contra must exactly negate. When absent (legacy/carwash), fall back to the GROSS
    // template.
    Optional<JournalEntrySaleView> originalEntry =
        (event.saleId() != null)
            ? journalEntryRepository.findBySaleAggregateId(event.saleId())
            : Optional.empty();
    List<JournalLineReversalView> originalLines =
        originalEntry.isPresent()
            ? journalLineRepository.findLinesByEntryId(originalEntry.get().getId())
            : List.of();

    // 1) Contra dimensional ledger posting. Negate the sale's GRAND TOTAL (reconstructed from the
    //    original tender legs), NOT event.amount(): SaleVoided.amount is the PAYMENT amount, which
    //    for a gift-card-settled sale is only the cash residual (grand − gift_card). The SALE
    //    ledger_posting was booked with the full grand total, so a residual-sized contra would
    // leave
    //    the gift-card portion standing and overstate the trial balance / unit-P&L / consolidation.
    //    Legacy sales with no original entry fall back to event.amount() (== grand total there).
    Money saleGrandTotal =
        originalEntry.isPresent()
            ? resolveGrandTotal(originalEntry.get(), originalLines, event.occurredAt(), currencyCode)
            : amount;
    Money negatedGross = saleGrandTotal.negate();
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negatedGross,
            glAccountCode,
            event.voidId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2+3) Phase 2 per-leg GL reversal: negate each component line of the original SALE entry
    //      exactly (debit ↔ credit) and unwind the read models by the NET revenue.
    if (originalEntry.isPresent()) {
      JournalEntrySaleView origEntryView = originalEntry.get();

      // Resolve the net revenue to unwind from the V19 net_revenue_minor column (the precomputed
      // net = subtotal − discount stored by RevenuePostingWriter at SALE posting time). For entries
      // predating V19 (null), fall back to the grand total (net == gross for Phase 1/legacy).
      Money netRevenue = resolveNetRevenue(origEntryView, amount, currencyCode);
      Money negatedNet = netRevenue.negate();
      boolean usesIllustrative = origEntryView.getUsesIllustrativeRules();

      // Unwind consolidated_revenue by the NET amount (not grand total).
      jdbcTemplate.update(
          UPSERT_REVENUE_SQL,
          UUID.randomUUID(),
          period,
          negatedNet.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      // Unwind outlet_revenue by the same NET amount, keyed on the event's business_id.
      jdbcTemplate.update(
          UPSERT_OUTLET_REVENUE_SQL,
          UUID.randomUUID(),
          event.businessId(),
          period,
          negatedNet.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      // Unwind P&L REVENUE leg by the NET amount; OR-propagate the original illustrative flag.
      pnlReadModel.addRevenue(period, negatedNet, companyId, actor, usesIllustrative);

      // Per-leg contra GL entry (uses pre-fetched lines to avoid a second DB round-trip).
      buildAndSavePerLegReversalEntryFromLines(
          origEntryView,
          originalLines,
          period,
          event.occurredAt(),
          event.voidId(),
          "SaleVoided (per-leg unwind)",
          companyId);
    } else {
      log.debug(
          "SaleVoided: no original SALE entry found for saleId={}; using GROSS template fall-back",
          event.saleId());
      // Legacy/carwash: net == grand total; unwind by grand total.
      jdbcTemplate.update(
          UPSERT_REVENUE_SQL,
          UUID.randomUUID(),
          period,
          negatedGross.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      // Unwind outlet_revenue by the grand total (net == gross for legacy sales).
      jdbcTemplate.update(
          UPSERT_OUTLET_REVENUE_SQL,
          UUID.randomUUID(),
          event.businessId(),
          period,
          negatedGross.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      pnlReadModel.addRevenue(period, negatedGross, companyId, actor, false);

      AccountRole clearingRole = resolveClearingRole(event.tenderType());
      JournalEntry glEntry =
          journalPostingService.buildEntry(
              EventKind.SALE_VOID,
              period,
              amount,
              event.occurredAt(),
              event.voidId(),
              "SaleVoided",
              false,
              clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
      saveEntryAndLines(glEntry, companyId);
    }
  }

  private void postRefundReversal(SaleRefundedEvent event) {
    Money refundAmount = event.refundAmount();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 1) Contra dimensional ledger posting (negated refund amount, REVENUE type, REVERSAL role).
    Money negatedRefund = refundAmount.negate();
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negatedRefund,
            glAccountCode,
            event.refundId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2+3) Full vs partial refund: look up the original SALE entry by sale_aggregate_id.
    //      FULL refund: reverse per-leg (same as void), unwind read models by the original NET.
    //      PARTIAL refund: integer proration cannot be guaranteed to produce a balanced GL entry,
    //      so partial refunds are rejected with PartialRefundNotSupportedException (HTTP 400).
    //      Legacy (no original SALE entry): fall back to the 2-line GROSS template.
    Optional<JournalEntrySaleView> originalEntry =
        (event.saleId() != null)
            ? journalEntryRepository.findBySaleAggregateId(event.saleId())
            : Optional.empty();

    if (originalEntry.isPresent()) {
      JournalEntrySaleView origEntryView = originalEntry.get();
      List<JournalLineReversalView> originalLines =
          journalLineRepository.findLinesByEntryId(origEntryView.getId());

      // Compute the original grand total from the original GL entry's TENDER legs (Σdebit EXCLUDING
      // the contra-revenue debits). A naive Σ-all-debits OVERSTATES the grand total by discount +
      // loyalty (the V37 SALE v3 debit side is amount + discount + loyalty), which wrongly
      // classified EVERY discounted / points / gift-card full refund as "partial" and rejected it —
      // the sale then stayed counted as revenue forever. See resolveSaleGrandTotal.
      String currencyCode = refundAmount.currency().getCurrencyCode();
      long originalGrandTotalMinor =
          resolveGrandTotal(origEntryView, originalLines, event.occurredAt(), currencyCode)
              .amountMinor();
      long refundMinor = refundAmount.amountMinor();

      if (refundMinor < originalGrandTotalMinor) {
        // Partial refund: reject. Proration would require distributing each leg proportionally
        // with integer rounding that cannot be guaranteed balanced. Ship a clear 400 error
        // rather than an incoherent or unbalanced GL posting. The processOnce claim is
        // NOT yet consumed (this throw rolls back the transaction).
        //
        // KNOWN LIMITATION (bug audit W2): a GIFT-CARD-settled sale can only be refunded up to its
        // PAYMENT (the cash residual = grand − gift_card), so its "full" refund carries the residual,
        // which is < grand total and lands HERE as partial. That is correct-by-definition — refunding
        // only the cash leaves the gift-card portion un-refunded — but it means a gift-card sale
        // cannot be fully refunded through this flow yet: restoring the gift-card balance is a
        // separate, unmodeled concern (loyalty-service re-credit). Discount/loyalty (non-gift-card)
        // full refunds DO work — their payment equals the grand total. Tracked as a residual.
        throw new PartialRefundNotSupportedException(
            "Partial refund not yet supported: refundAmount="
                + refundMinor
                + " < originalGrandTotal="
                + originalGrandTotalMinor
                + " for saleId="
                + event.saleId()
                + ". Full per-leg reversal is supported; partial proration is deferred.");
      }

      // Full refund: resolve net revenue from V19 net_revenue_minor and unwind read models by NET.
      Money netRevenue = resolveNetRevenue(origEntryView, refundAmount, currencyCode);
      Money negatedNet = netRevenue.negate();
      boolean usesIllustrative = origEntryView.getUsesIllustrativeRules();

      jdbcTemplate.update(
          UPSERT_REVENUE_SQL,
          UUID.randomUUID(),
          period,
          negatedNet.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      // Unwind outlet_revenue by the same NET amount, keyed on the event's business_id.
      jdbcTemplate.update(
          UPSERT_OUTLET_REVENUE_SQL,
          UUID.randomUUID(),
          event.businessId(),
          period,
          negatedNet.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      pnlReadModel.addRevenue(period, negatedNet, companyId, actor, usesIllustrative);

      // Per-leg contra GL entry (uses pre-fetched lines).
      buildAndSavePerLegReversalEntryFromLines(
          origEntryView,
          originalLines,
          period,
          event.occurredAt(),
          event.refundId(),
          "SaleRefunded (per-leg full unwind)",
          companyId);
    } else {
      log.debug(
          "SaleRefunded: no original SALE entry found for saleId={}; using GROSS template fall-back",
          event.saleId());
      // Legacy/carwash path: net == grand total.
      String currencyCode = refundAmount.currency().getCurrencyCode();
      jdbcTemplate.update(
          UPSERT_REVENUE_SQL,
          UUID.randomUUID(),
          period,
          negatedRefund.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      // Unwind outlet_revenue by the grand total (net == gross for legacy sales).
      jdbcTemplate.update(
          UPSERT_OUTLET_REVENUE_SQL,
          UUID.randomUUID(),
          event.businessId(),
          period,
          negatedRefund.amountMinor(),
          currencyCode,
          actor,
          actor,
          companyId);

      pnlReadModel.addRevenue(period, negatedRefund, companyId, actor, false);

      AccountRole clearingRole = resolveClearingRole(event.tenderType());
      JournalEntry glEntry =
          journalPostingService.buildEntry(
              EventKind.SALE_REFUND,
              period,
              refundAmount,
              event.occurredAt(),
              event.refundId(),
              "SaleRefunded",
              false,
              clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
      saveEntryAndLines(glEntry, companyId);
    }
  }

  /**
   * Builds and saves a balanced contra journal entry by negating each pre-fetched line of the
   * original SALE entry (debit ↔ credit swap). The contra entry uses the same account codes as the
   * original, reversing the CLEARING debit → CLEARING credit and each revenue/liability credit →
   * debit. This unwinds all Phase 2 component legs: GROSS_REVENUE, SALES_DISCOUNT,
   * SERVICE_CHARGE_REVENUE, and TAX_PAYABLE exactly.
   *
   * <p>If {@code originalLines} is empty (should not occur for a valid SALE entry), logs an error
   * and returns without saving — the {@code processOnce} claim has already been consumed, so the
   * event is not re-delivered and the operator must investigate (money is not silently dropped from
   * the GL; the dimensional ledger posting was already written in step 1 above).
   *
   * <p>The caller is responsible for pre-fetching the lines to avoid a redundant DB round-trip when
   * the caller also needs the lines to derive the net revenue (e.g. for read-model unwind).
   */
  private void buildAndSavePerLegReversalEntryFromLines(
      JournalEntrySaleView originalEntry,
      List<JournalLineReversalView> originalLines,
      String period,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      String companyId) {

    if (originalLines.isEmpty()) {
      log.error(
          "Per-leg reversal: no lines found for journal_entry id={}; this should not occur"
              + " for a valid SALE entry. Void/refund GL entry skipped — check seed data."
              + " sourceEventId={}",
          originalEntry.getId(),
          sourceEventId);
      return;
    }

    UUID contraEntryId = UUID.randomUUID();
    String currency = originalEntry.getCurrency().strip();
    boolean usesIllustrative = originalEntry.getUsesIllustrativeRules();

    // Negate each line: original DEBIT → contra CREDIT; original CREDIT → contra DEBIT.
    // The contra entry mirrors the original line ordering (same line_no sequence) for traceability.
    List<JournalLine> contraLines = new ArrayList<>(originalLines.size());
    int lineNo = 1;
    for (JournalLineReversalView orig : originalLines) {
      if (orig.getDebitMinor() > 0) {
        // Original debit line → contra credit line (same account, same amount).
        Money origDebit = Money.ofMinor(orig.getDebitMinor(), currency);
        contraLines.add(
            JournalLine.credit(contraEntryId, lineNo, orig.getAccountCode(), origDebit));
      } else {
        // Original credit line → contra debit line (same account, same amount).
        Money origCredit = Money.ofMinor(orig.getCreditMinor(), currency);
        contraLines.add(
            JournalLine.debit(contraEntryId, lineNo, orig.getAccountCode(), origCredit));
      }
      lineNo++;
    }

    JournalEntry contraEntry =
        JournalEntry.balanced(
            contraEntryId,
            period,
            occurredAt,
            description,
            currency,
            sourceEventId,
            usesIllustrative,
            contraLines);
    saveEntryAndLines(contraEntry, companyId);
  }

  /**
   * Resolves the net revenue for read-model unwind from the V19 {@code net_revenue_minor} column
   * stored on the original SALE journal entry at posting time. This is the precomputed {@code
   * subtotal − discount} value that {@link
   * id.co.nativeapp.finance.revenue.service.RevenuePostingWriter} accumulated.
   *
   * <p>Falls back to the grand total ({@code grandTotal}) for SALE entries predating V19 (null
   * {@code net_revenue_minor}) — for Phase 1/legacy sales, net == grand total, so the fallback is
   * correct.
   */
  private static Money resolveNetRevenue(
      JournalEntrySaleView originalEntry, Money grandTotal, String currencyCode) {
    Long netMinor = originalEntry.getNetRevenueMinor();
    if (netMinor != null) {
      return Money.ofMinor(netMinor, currencyCode);
    }
    // V19 column absent (pre-V19 or non-Phase-2 entry): net == gross for legacy Phase 1 sales.
    return grandTotal;
  }

  /**
   * The sale's GRAND TOTAL. Prefers the value STORED on the SALE entry at posting time (V38 {@code
   * grand_total_minor}) — stable regardless of any later {@code role_account_map} remap. Falls back
   * to reconstructing it from the GL lines only for SALE entries predating V38 ({@code null} stored
   * value), which is correct against the immutable v1 seed those entries were posted under.
   */
  private Money resolveGrandTotal(
      JournalEntrySaleView entry,
      List<JournalLineReversalView> lines,
      Instant asOf,
      String currencyCode) {
    Long stored = entry.getGrandTotalMinor();
    if (stored != null) {
      return Money.ofMinor(stored, currencyCode);
    }
    return resolveSaleGrandTotal(lines, asOf, currencyCode);
  }

  /**
   * The sale's GRAND TOTAL (what the customer actually owed/paid) reconstructed from the original
   * SALE entry's TENDER debit legs = Σ(debit legs) − Σ(contra-revenue debit legs).
   *
   * <p>The raw debit sum is NOT the grand total: the V37 SALE v3 template's debit side is {@code
   * NET_TENDER + GIFT_CARD_TENDER + SALES_DISCOUNT + LOYALTY_DISCOUNT}, i.e. {@code amount +
   * discount + loyalty} (the gift-card split nets within the tender). The two contra-revenue debits
   * (SALES_DISCOUNT, LOYALTY_DISCOUNT) inflate the sum above the grand total, so they are excluded
   * — leaving exactly the tender legs, which sum to {@code amount}. Works across every template
   * version: legacy 2-line GROSS (no contra legs → Σdebit = grand), v2 (SALES_DISCOUNT only), v3
   * (both). Credit legs contribute 0 to a debit sum, so they are naturally ignored.
   *
   * <p>The contra-revenue account codes are resolved at {@code asOf}; the {@code role_account_map}
   * for these two roles is seeded open-ended (2000-01-01..9999-12-31) and effectively immutable, so
   * resolving at the reversal instant matches the codes stamped on the original lines at sale time.
   */
  private Money resolveSaleGrandTotal(
      List<JournalLineReversalView> originalLines, Instant asOf, String currencyCode) {
    Set<String> contraRevenueCodes = new HashSet<>();
    for (AccountRole contraRole :
        List.of(AccountRole.SALES_DISCOUNT, AccountRole.LOYALTY_DISCOUNT)) {
      String code = roleAccountResolver.resolve(contraRole, asOf);
      if (code != null) {
        contraRevenueCodes.add(code);
      }
    }
    long grandTotalMinor =
        originalLines.stream()
            .filter(line -> !contraRevenueCodes.contains(line.getAccountCode()))
            .mapToLong(JournalLineReversalView::getDebitMinor)
            .sum();
    return Money.ofMinor(grandTotalMinor, currencyCode);
  }

  private void saveEntryAndLines(JournalEntry glEntry, String companyId) {
    glEntry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(glEntry);
    glEntry
        .getLines()
        .forEach(
            line -> {
              line.setCompanyId(companyId);
              journalLineRepository.save(line);
            });
  }

  /**
   * Resolves the GL clearing {@link AccountRole} from the original tender type (same logic as
   * {@code RevenuePostingWriter.resolveClearingRole} — inline to avoid cross-feature coupling).
   */
  static AccountRole resolveClearingRole(String tenderType) {
    if (tenderType == null) {
      return AccountRole.CASH_CLEARING;
    }
    return switch (tenderType) {
      case "CASH" -> AccountRole.CASH_CLEARING;
      case "QRIS" -> AccountRole.QRIS_CLEARING;
      case "CARD" -> AccountRole.CARD_CLEARING;
      default -> AccountRole.CASH_CLEARING;
    };
  }
}
