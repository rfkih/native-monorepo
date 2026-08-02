package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseSettlementRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts the exact contra of a consumed
 * {@code ExpenseClaimVoided} — the correction path for an APPROVED, un-settled claim (ADR 0030).
 * Unwinds the dimensional {@code ledger_posting} EXPENSE leg AND the P&amp;L expense accumulator by
 * the negated claim amount, and writes a {@link EventKind#EXPENSE_CLAIM_VOID} double-entry GL
 * contra (Dr {@code 2600 Employee Expense Payable} / Cr the generic EXPENSE role — V39).
 *
 * <p>A distinct bean from {@link ExpenseClaimPostingService} so the method is invoked through the
 * Spring proxy (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The {@link ProcessedEventStore#processOnce}
 * dedupe claim and the side effects commit (or roll back) together, exactly like {@link
 * ExpenseClaimPostingWriter}.
 *
 * <p><strong>Same account, contra amount.</strong> The dimensional contra resolves the SAME expense
 * account the original approval posted to — {@link GlAccountResolver#resolveExpense} evaluated at
 * the ORIGINAL {@code approvedAt} (so it reverses the exact account even if the mapping has since
 * changed) — and hits the SAME {@code business_id} (the catalog's explicit contract: "the contra
 * hits the same business_id"). The P&amp;L unwind negates the amount into the SAME accumulator the
 * approval added to (the labor-supersession {@code REVERSAL} idiom: {@code
 * PnlReadModelWriter#addExpense} with a negated {@link Money}).
 *
 * <p><strong>Settled-already defense-in-depth.</strong> The producer guards that a settled or
 * payroll-linked claim can never void (ADR 0030 §5), but finance independently re-checks the
 * settle-once guard: a void arriving after a settlement is a LOUD logged skip (no amounts logged),
 * never a silent reversal of money that has already moved — a human follow-up case.
 */
@Component
public class ExpenseClaimVoidWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimVoidWriter.class);

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final EmployeeExpenseSettlementRepository settlementRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ExpenseClaimVoidWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      EmployeeExpenseSettlementRepository settlementRepository) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.settlementRepository = settlementRepository;
  }

  /**
   * Posts the void's contra to the ledger and unwinds the P&amp;L read model, exactly once per
   * event id. Must be called inside a {@link TenantContext} scope bound to the event's {@code
   * company_id}.
   *
   * @return {@code true} if this delivery ran (first delivery — which may still be a no-op contra
   *     when the settle-once guard already has a row for the claim, see below), {@code false} if
   *     skipped as a duplicate (re-delivery of the same event id)
   */
  @Transactional
  public boolean postVoided(ExpenseClaimVoidedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postVoidReversal(event));
  }

  private void postVoidReversal(ExpenseClaimVoidedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // Defense-in-depth: money already moved via a settlement must never be silently touched again.
    // No amounts are logged (rule 6 discipline extended to a defensive log line).
    if (settlementRepository.existsByClaimId(event.claimId())) {
      log.warn(
          "ExpenseClaimVoided arrived for an ALREADY-SETTLED claim claimId={} (eventId={});"
              + " skipping — money has already moved, this requires human follow-up",
          event.claimId(),
          event.eventId());
      return;
    }

    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.voidedAt());

    // Resolve the SAME account the original approval posted to: the mapping_rule effective AT the
    // ORIGINAL approvedAt, so the contra reverses the exact account even if the mapping has since
    // changed.
    GlAccountResolution resolution =
        glAccountResolver.resolveExpense(event.glHint(), event.approvedAt());

    // 1) Contra dimensional ledger posting — hits the SAME business_id the approval posted under,
    //    negated amount, marked REVERSAL for audit traceability (the ReversalPostingWriter idiom).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.orgUnitId(),
            period,
            amount.negate(),
            resolution.accountCode(),
            event.eventId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind the P&L EXPENSE leg — negate into the SAME accumulator the approval added to (the
    //    labor-supersession REVERSAL idiom: PnlReadModelWriter#addExpense with a negated amount).
    pnlReadModel.addExpense(period, amount.negate(), companyId, actor);

    // 3) Double-entry GL contra — Dr EMPLOYEE_EXPENSE_PAYABLE / Cr EXPENSE (V39 illustrative
    //    template, the exact contra of EXPENSE_CLAIM_APPROVED).
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.EXPENSE_CLAIM_VOID,
            period,
            amount,
            event.voidedAt(),
            event.eventId(),
            "ExpenseClaimVoided",
            false);
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
}
