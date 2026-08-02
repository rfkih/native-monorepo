package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseSettlement;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseSettlementRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work that settle a consumed {@code
 * ExpenseReimbursementSettled} — a balance-sheet-only move (Dr {@code 2600 Employee Expense
 * Payable} / Cr CASH_CLEARING, the {@link EventKind#EXPENSE_CLAIM_SETTLED} V39 template); the
 * expense itself was already recognised at approval, so NO P&amp;L leg is touched here.
 *
 * <p>A distinct bean from {@link ExpenseClaimPostingService} so each method is invoked through the
 * Spring proxy (rule 5) — including {@link #existsByClaimIdForReplay}, which MUST run in an
 * independent fresh transaction to recover a conflict (a PostgreSQL transaction is poisoned once a
 * constraint fires — the {@code GiftCardSaleWriter}/{@code SaleWriter} conflict-recovery idiom).
 *
 * <p><strong>Two layers of idempotency (ADR 0030 §7).</strong>
 *
 * <ol>
 *   <li>{@link ProcessedEventStore#processOnce}, keyed by the event UUID — dedupes a Kafka
 *       re-delivery of the identical event.
 *   <li>The {@code employee_expense_settlement} SETTLE-ONCE guard, keyed by {@code claimId} — this
 *       is a DIFFERENT invariant: a payroll-supersession re-emission arrives under a DIFFERENT
 *       event UUID for the SAME claim, so {@code processOnce} alone would let it through. {@link
 *       #settle} fast-pre-checks the guard and, if a row already exists, logs an info line and
 *       returns WITHOUT posting (no amounts logged). Otherwise it inserts the guard row and the
 *       settlement journal entry in the SAME transaction. A concurrent racer for the SAME claim (a
 *       genuinely simultaneous re-emission racing the fast pre-check) trips the {@code UNIQUE
 *       (company_id, claim_id)} constraint on the guard-row insert/flush — the whole transaction
 *       aborts (a UNIQUE race loser must not double-post) and {@link ExpenseClaimPostingService}
 *       recovers with a separate-transaction re-read via {@link #existsByClaimIdForReplay},
 *       resolving to the same logged no-op the fast pre-check would have taken.
 * </ol>
 */
@Component
public class ExpenseSettlementWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpenseSettlementWriter.class);

  private final ProcessedEventStore processedEvents;
  private final EmployeeExpenseSettlementRepository settlementRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  public ExpenseSettlementWriter(
      ProcessedEventStore processedEvents,
      EmployeeExpenseSettlementRepository settlementRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.processedEvents = processedEvents;
    this.settlementRepository = settlementRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Settles the event's claim, exactly once per event id (Kafka re-delivery) AND at most once per
   * claim (the settle-once guard). Must be called inside a {@link TenantContext} scope bound to the
   * event's {@code company_id}.
   *
   * @return {@code true} if this delivery ran (first delivery of this event id — which may still be
   *     a logged no-op when the claim is already settled), {@code false} if skipped as a duplicate
   *     re-delivery of the same event id
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer for the
   *     SAME claim (a different event id) wins the settle-once guard's UNIQUE constraint after this
   *     method's own fast pre-check passed — recovered by {@link ExpenseClaimPostingService}
   */
  @Transactional
  public boolean settle(ExpenseReimbursementSettledEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> settleClaim(event));
  }

  private void settleClaim(ExpenseReimbursementSettledEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    if (settlementRepository.existsByClaimId(event.claimId())) {
      log.info(
          "ExpenseReimbursementSettled: claimId={} is already settled; skipping (settle-once, ADR"
              + " 0030 §7 — a Kafka re-delivery or a payroll-supersession re-emission, no amounts"
              + " logged)",
          event.claimId());
      return;
    }

    String period = LedgerPosting.periodOf(event.settledAt());
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.EXPENSE_CLAIM_SETTLED,
            period,
            event.amount(),
            event.settledAt(),
            event.eventId(),
            "ExpenseReimbursementSettled",
            false);

    // Claim the settle-once guard FIRST — before persisting the journal entry — so a concurrent
    // racer for the SAME claim trips the UNIQUE(company_id, claim_id) constraint here and the whole
    // transaction (guard row AND, since neither has been saved yet, the entry) rolls back before
    // any
    // partial write lands: a UNIQUE race loser must not double-post.
    EmployeeExpenseSettlement guard =
        new EmployeeExpenseSettlement(
            event.claimId(),
            event.settlementKind(),
            event.payrollRunId(),
            event.runSeq(),
            glEntry.getId(),
            event.settledAt());
    guard.setCompanyId(companyId);
    settlementRepository.saveAndFlush(guard);

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
   * Re-checks the settle-once guard in a FRESH, independent transaction — used by {@link
   * ExpenseClaimPostingService} to recover the loser of a concurrent settle-once race after its own
   * {@link #settle} transaction aborted with a {@link
   * org.springframework.dao.DataIntegrityViolationException}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean existsByClaimIdForReplay(UUID claimId) {
    return settlementRepository.existsByClaimId(claimId);
  }
}
