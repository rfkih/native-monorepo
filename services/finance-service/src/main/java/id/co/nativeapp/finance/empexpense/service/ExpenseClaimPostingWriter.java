package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseClaimLedgerRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts a consumed {@code
 * ExpenseClaimApproved} to the dimensional ledger as an EXPENSE posting and accumulates the P&amp;L
 * read model's expense leg — idempotently (ADR 0030, expense-claims program). Mirrors {@code
 * ExpensePostingWriter} exactly, extended with the claim's own dimension ({@code org_unit_id}) and
 * a NEW double-entry template ({@link EventKind#EXPENSE_CLAIM_APPROVED}: Dr the generic EXPENSE
 * role / Cr {@code 2600 Employee Expense Payable}, V39).
 *
 * <p>It is a distinct bean (not a private method on {@link ExpenseClaimPostingService}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC — the tenant GUC is exactly what makes the RLS {@code WITH CHECK} pass on the
 * inserts (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Everything below happens in ONE transaction: the
 * {@link ProcessedEventStore#processOnce} dedupe claim and the side effects commit (or roll back)
 * together. A re-delivered {@code ExpenseClaimApproved} (same event id) is a clean no-op. The
 * {@code source_event_id UNIQUE} constraint on {@code ledger_posting} + {@code journal_entry} is
 * the database backstop.
 *
 * <p><strong>Resolution + fail-safe.</strong> The DIMENSIONAL {@code
 * ledger_posting.gl_account_code} is resolved on write from the claim's {@code gl_hint} via the
 * versioned, effective-dated {@code mapping_rule} ({@link GlAccountResolver#resolveExpense}); an
 * unmappable hint FAILS SAFE to the suspense account (money is never dropped, HR-3). The
 * DOUBLE-ENTRY GL leg stays on the generic EXPENSE role (5000) for every category, exactly like the
 * existing {@code ExpenseRecorded} path — only the dimensional posting is category-specific (see
 * the V39 migration header).
 *
 * <p><strong>Claim-ledger upsert (ADR 0030 §4/§7, review W1/S3).</strong> After posting, this
 * writer upserts the per-claim {@link EmployeeExpenseClaimLedger} row (the settle-once guard AND
 * the employee-payable drill-down source): the NORMAL, in-order case INSERTS a fresh row with the
 * recognition fields; if a row ALREADY exists — an out-of-order settlement self-healed an
 * unrecognized-claim row before this approval arrived — the recognition fields are UPDATED onto it
 * and the reconciliation is logged INFO. This insert is not wrapped in the settle-once
 * UNIQUE-race-recovery (unlike {@code ExpenseSettlementWriter}): approval happens exactly once per
 * claim in the employee-service state machine, so a concurrent INSERT-vs-INSERT race here is not a
 * realistic production scenario; a genuinely unlucky race would surface as an unhandled {@code
 * DataIntegrityViolationException} which the Kafka consumer's bounded-retry error handler retries —
 * the retry re-reads and takes the UPDATE branch, self-healing.
 */
@Component
public class ExpenseClaimPostingWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimPostingWriter.class);

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final EmployeeExpenseClaimLedgerRepository claimLedgerRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ExpenseClaimPostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      GeneralLedgerWriter generalLedgerWriter,
      EmployeeExpenseClaimLedgerRepository claimLedgerRepository) {
    this.ledgerRepository = ledgerRepository;
    this.generalLedgerWriter = generalLedgerWriter;
    this.processedEvents = processedEvents;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.claimLedgerRepository = claimLedgerRepository;
  }

  /**
   * Posts the event's expense recognition to the ledger and accumulates the P&amp;L read model,
   * exactly once per event id. Must be called inside a {@link TenantContext} scope bound to the
   * event's {@code company_id} (the auto-RLS aspect sets the tenant GUC for this transaction from
   * that scope).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean postApproved(ExpenseClaimApprovedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postApprovedExpense(event));
  }

  private void postApprovedExpense(ExpenseClaimApprovedEvent event) {
    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.approvedAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // 0) GUARD the single-base-currency invariant (#26) — same guard ExpensePostingWriter calls.
    pnlReadModel.requireConsistentCurrency(period, amount);

    // 1) RESOLVE the EXPENSE gl_account via the versioned, effective-dated mapping_rule from the
    //    claim's gl_hint (CQRS: resolve on write). An unmappable hint fails safe to the suspense
    //    account — the money is still posted, never dropped (HR-3).
    GlAccountResolution resolution =
        glAccountResolver.resolveExpense(event.glHint(), event.approvedAt());
    if (!resolution.mapped()) {
      log.warn(
          "Unmappable ExpenseClaimApproved gl_hint='{}' (claimId={}, eventId={}); posting to"
              + " suspense account {} — money is preserved on the books, not dropped",
          event.glHint(),
          event.claimId(),
          event.eventId(),
          resolution.accountCode());
    }

    // 2) Append the immutable, dimensional EXPENSE ledger posting under the claim's org_unit_id.
    //    source_event_id is UNIQUE.
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.orgUnitId(),
            period,
            amount,
            resolution.accountCode(),
            event.eventId());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 3) Atomically accumulate the P&L read model's EXPENSE leg in the SAME transaction.
    pnlReadModel.addExpense(period, amount, companyId, actor);

    // 4) Double-entry GL journal — SAME transaction, SAME processOnce claim. Dr EXPENSE (generic
    //    role) / Cr EMPLOYEE_EXPENSE_PAYABLE (V39 illustrative template).
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.EXPENSE_CLAIM_APPROVED,
            period,
            amount,
            event.approvedAt(),
            event.eventId(),
            "ExpenseClaimApproved",
            false);
    glEntry.setCompanyId(companyId);
    // saveAndFlush flushes the journal_entry INSERT to Postgres immediately so the FK on
    // journal_line.entry_id is satisfied when the line INSERTs follow in the same transaction.
    generalLedgerWriter.post(glEntry, companyId);

    // 5) Upsert the claim-ledger row (ADR 0030 §4 drill-down + §7 reconciliation signal, review
    //    W1/S3): normal in-order case INSERTS a fresh recognition-only row; if a row already
    //    exists — an out-of-order settlement self-healed it first — UPDATE the recognition fields
    //    onto it and log the reconciliation.
    Optional<EmployeeExpenseClaimLedger> existing =
        claimLedgerRepository.findByClaimId(event.claimId());
    if (existing.isPresent()) {
      EmployeeExpenseClaimLedger row = existing.get();
      row.reconcileRecognition(event.approvedAt(), glEntry.getId());
      claimLedgerRepository.save(row);
      log.info(
          "ExpenseClaimApproved: claimId={} reconciled — approval arrived after settlement"
              + " (out-of-order delivery), no amounts logged",
          event.claimId());
    } else {
      EmployeeExpenseClaimLedger row =
          EmployeeExpenseClaimLedger.recognized(
              event.claimId(),
              event.employeeId(),
              event.orgUnitId(),
              amount,
              event.approvedAt(),
              glEntry.getId());
      row.setCompanyId(companyId);
      claimLedgerRepository.saveAndFlush(row);
    }
  }
}
