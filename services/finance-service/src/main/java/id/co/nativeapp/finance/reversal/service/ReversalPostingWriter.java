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
 * <p><strong>Phase 2 per-leg reversal (void only).</strong> When the original SALE entry can be
 * looked up via {@code journal_entry.sale_aggregate_id} (set by {@code RevenuePostingWriter} for
 * SALE entries — V18), the reversal negates each component line exactly: debit ↔ credit swap. This
 * reverses GROSS_REVENUE, DISCOUNT, SERVICE_CHARGE_REVENUE, and TAX_PAYABLE legs individually. When
 * no original SALE entry is found (legacy/carwash sales predating Phase 2, or void of a non-POS
 * sale), the writer falls back to the 2-line GROSS template (Phase 1 behaviour).
 *
 * <p><strong>Read-model reversal.</strong> The {@code consolidated_revenue} and {@code
 * consolidated_pnl} read models are accumulated with the negated amount (grand total for voids,
 * refundAmount for refunds). The GL journal entry is the authoritative record; the read-model
 * amounts are approximations acceptable for the dashboard.
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
    Money negated = amount.negate();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 1) Contra dimensional ledger posting (negated amount, REVENUE type, REVERSAL role).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negated,
            glAccountCode,
            event.voidId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind consolidated-revenue + P&L read models with the negated grand-total amount.
    //    The read model was accumulated with net revenue (subtotal − discount) in Phase 2, but
    //    SaleVoidedEvent does not carry a breakdown. Using grand total for the unwind is a known
    //    Phase 2 approximation; the GL journal entry (per-leg negation below) is authoritative.
    String currencyCode = amount.currency().getCurrencyCode();
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        negated.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3) Unwind the P&L read model's REVENUE leg.
    pnlReadModel.addRevenue(period, negated, companyId, actor);

    // 4) Phase 2 per-leg GL reversal: if the original SALE entry exists (via sale_aggregate_id
    //    set by RevenuePostingWriter — V18), negate each component line exactly (debit ↔ credit).
    //    Otherwise fall back to the SALE_VOID 2-line GROSS template (legacy/carwash behaviour).
    Optional<JournalEntrySaleView> originalEntry =
        (event.saleId() != null)
            ? journalEntryRepository.findBySaleAggregateId(event.saleId())
            : Optional.empty();

    if (originalEntry.isPresent()) {
      buildAndSavePerLegReversalEntry(
          originalEntry.get(),
          period,
          event.occurredAt(),
          event.voidId(),
          "SaleVoided (per-leg unwind)",
          companyId);
    } else {
      log.debug(
          "SaleVoided: no original SALE entry found for saleId={}; using GROSS template fall-back",
          event.saleId());
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
    Money negated = refundAmount.negate();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 1) Contra dimensional ledger posting (negated refund amount, REVENUE type, REVERSAL role).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negated,
            glAccountCode,
            event.refundId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind consolidated-revenue read model by the negated refund amount.
    String currencyCode = refundAmount.currency().getCurrencyCode();
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        negated.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3) Unwind the P&L read model's REVENUE leg.
    pnlReadModel.addRevenue(period, negated, companyId, actor);

    // 4) SALE_REFUND GL journal entry (2-line GROSS template). Per-leg refund reversal is deferred
    //    until SaleRefunded v2 carries per-component breakdown fields (a future event evolution).
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

  /**
   * Builds and saves a balanced contra journal entry by negating each line of the original SALE
   * entry (debit ↔ credit swap). The contra entry uses the same account codes as the original,
   * reversing the CLEARING debit → CLEARING credit and each revenue/liability credit → debit. This
   * unwinds all Phase 2 component legs: GROSS_REVENUE, SALES_DISCOUNT, SERVICE_CHARGE_REVENUE, and
   * TAX_PAYABLE exactly.
   *
   * <p>If the original entry has no lines (should not occur for a valid SALE entry), logs an error
   * and returns without saving — the {@code processOnce} claim has already been consumed, so the
   * event is not re-delivered and the operator must investigate (money is not silently dropped from
   * the GL; the dimensional ledger posting was already written in step 1 above).
   */
  private void buildAndSavePerLegReversalEntry(
      JournalEntrySaleView originalEntry,
      String period,
      Instant occurredAt,
      UUID sourceEventId,
      String description,
      String companyId) {

    List<JournalLineReversalView> originalLines =
        journalLineRepository.findLinesByEntryId(originalEntry.getId());

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
