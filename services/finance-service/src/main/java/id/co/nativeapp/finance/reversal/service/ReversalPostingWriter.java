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
import java.util.List;
import java.util.Optional;
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

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ReversalPostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      JdbcTemplate jdbcTemplate,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
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

    // 1) Contra dimensional ledger posting (negated amount, REVENUE type, REVERSAL role).
    Money negatedGross = amount.negate();
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

    // 2+3) Phase 2 per-leg GL reversal: look up the original SALE entry by sale_aggregate_id
    //      (set by RevenuePostingWriter — V18). When found, negate each component line exactly
    //      (debit ↔ credit) and derive the NET revenue to unwind from the original entry's lines:
    //      netRevenue = GROSS_REVENUE credit − SALES_DISCOUNT debit.
    //      When no original SALE entry is found (legacy/carwash), fall back to the 2-line GROSS
    //      template and unwind by the grand total (net == gross for legacy sales).
    Optional<JournalEntrySaleView> originalEntry =
        (event.saleId() != null)
            ? journalEntryRepository.findBySaleAggregateId(event.saleId())
            : Optional.empty();

    if (originalEntry.isPresent()) {
      JournalEntrySaleView origEntryView = originalEntry.get();
      List<JournalLineReversalView> originalLines =
          journalLineRepository.findLinesByEntryId(origEntryView.getId());

      // Resolve the net revenue to unwind from the V19 net_revenue_minor column (the precomputed
      // net = subtotal − discount stored by RevenuePostingWriter at SALE posting time). For entries
      // predating V19 (null), fall back to the grand total (net == gross for Phase 1/legacy).
      String currencyCode = amount.currency().getCurrencyCode();
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
      String currencyCode = amount.currency().getCurrencyCode();
      jdbcTemplate.update(
          UPSERT_REVENUE_SQL,
          UUID.randomUUID(),
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

      // Compute the original grand total from the original GL entry lines (Σdebit = grand total
      // for a SALE entry, since the CLEARING debit == the customer-pays amount).
      long originalGrandTotalMinor =
          originalLines.stream().mapToLong(JournalLineReversalView::getDebitMinor).sum();
      long refundMinor = refundAmount.amountMinor();

      if (refundMinor < originalGrandTotalMinor) {
        // Partial refund: reject. Proration would require distributing each leg proportionally
        // with integer rounding that cannot be guaranteed balanced. Ship a clear 400 error
        // rather than an incoherent or unbalanced GL posting. The processOnce claim is
        // NOT yet consumed (this throw rolls back the transaction).
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
      String currencyCode = refundAmount.currency().getCurrencyCode();
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
