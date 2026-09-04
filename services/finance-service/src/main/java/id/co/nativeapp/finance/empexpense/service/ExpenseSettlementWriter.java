package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseClaimLedgerRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
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
 * Spring proxy (rule 5) — including {@link #isSettledForReplay}, which MUST run in an independent
 * fresh transaction to recover a conflict (a PostgreSQL transaction is poisoned once a constraint
 * fires — the {@code GiftCardSaleWriter}/{@code SaleWriter} conflict-recovery idiom).
 *
 * <p><strong>Two layers of idempotency (ADR 0030 §7).</strong>
 *
 * <ol>
 *   <li>{@link ProcessedEventStore#processOnce}, keyed by the event UUID — dedupes a Kafka
 *       re-delivery of the identical event.
 *   <li>The {@code employee_expense_claim_ledger} SETTLE-ONCE guard, keyed by {@code claimId} —
 *       this is a DIFFERENT invariant: a payroll-supersession re-emission arrives under a DIFFERENT
 *       event UUID for the SAME claim, so {@code processOnce} alone would let it through. {@link
 *       #settle} branches three ways on the row for {@code claimId} (review W1/S3):
 *       <ul>
 *         <li><strong>settled already</strong> ({@code settledAt} non-null) — a logged INFO no-op,
 *             no posting.
 *         <li><strong>row exists, unsettled</strong> (the normal in-order case — the approval
 *             already recognised the claim) — UPDATE the settlement fields onto it.
 *         <li><strong>no row at all</strong> (settlement arrived before its approval, or the
 *             approval is permanently lost) — self-heal: INSERT a row carrying ONLY the settlement
 *             facts, logging a LOUD WARN (claim id only, no amounts) that account 2600 is unbacked
 *             by a recognition entry until the approval arrives (ADR 0030 §7).
 *       </ul>
 *       The INSERT branch is the one a concurrent racer for the SAME claim (a genuinely
 *       simultaneous settlement racing this fast pre-check, both finding no row) trips the {@code
 *       UNIQUE (company_id, claim_id)} constraint on — the whole transaction aborts (a UNIQUE race
 *       loser must not double-post) and {@link ExpenseClaimPostingService} recovers with a
 *       separate-transaction re-read via {@link #isSettledForReplay}, resolving to the same logged
 *       no-op the fast pre-check would have taken. A settlement landing on an ALREADY-EXISTING
 *       unsettled row is a plain UPDATE, not an insert, so it is NOT protected by this constraint —
 *       only by the inherited {@code Auditable @Version} optimistic lock (a residual, not exercised
 *       by this phase's tests).
 * </ol>
 */
@Component
public class ExpenseSettlementWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpenseSettlementWriter.class);

  private final ProcessedEventStore processedEvents;
  private final EmployeeExpenseClaimLedgerRepository claimLedgerRepository;
  private final JournalPostingService journalPostingService;
  private final GeneralLedgerWriter generalLedgerWriter;

  public ExpenseSettlementWriter(
      ProcessedEventStore processedEvents,
      EmployeeExpenseClaimLedgerRepository claimLedgerRepository,
      JournalPostingService journalPostingService,
      GeneralLedgerWriter generalLedgerWriter) {
    this.processedEvents = processedEvents;
    this.generalLedgerWriter = generalLedgerWriter;
    this.claimLedgerRepository = claimLedgerRepository;
    this.journalPostingService = journalPostingService;
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
   *     SAME claim (a different event id, both finding no existing row) wins the settle-once
   *     guard's UNIQUE constraint after this method's own fast pre-check passed — recovered by
   *     {@link ExpenseClaimPostingService}
   */
  @Transactional
  public boolean settle(ExpenseReimbursementSettledEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> settleClaim(event));
  }

  private void settleClaim(ExpenseReimbursementSettledEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    Optional<EmployeeExpenseClaimLedger> existing =
        claimLedgerRepository.findByClaimId(event.claimId());

    if (existing.isPresent() && existing.get().isSettled()) {
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
    // racer for the SAME claim trips the UNIQUE(company_id, claim_id) constraint here (INSERT
    // branch only) and the whole transaction rolls back before any partial write lands: a UNIQUE
    // race loser must not double-post.
    if (existing.isPresent()) {
      // Row already exists (recognized by an approval, un-settled) — a plain UPDATE, not an insert.
      EmployeeExpenseClaimLedger row = existing.get();
      row.stampSettlement(
          event.settledAt(),
          event.settlementKind(),
          event.payrollRunId(),
          event.runSeq(),
          glEntry.getId());
      claimLedgerRepository.save(row);
    } else {
      // No row at all: settlement before approval, or a permanently lost approval. Self-heal —
      // insert a row carrying ONLY the settlement facts (recognition null) and flag it loudly (ADR
      // 0030 §7): account 2600 now carries a debit unbacked by a recognition entry until the
      // approval arrives (or never, if it was lost — see the ADR §7 residual).
      log.warn(
          "ExpenseReimbursementSettled for an UNRECOGNIZED claim claimId={} — approval missing or"
              + " late; account 2600 unbacked by a recognition entry until it arrives",
          event.claimId());
      EmployeeExpenseClaimLedger row =
          EmployeeExpenseClaimLedger.unrecognizedSettlement(
              event.claimId(),
              event.employeeId(),
              event.orgUnitId(),
              event.amount(),
              event.settledAt(),
              event.settlementKind(),
              event.payrollRunId(),
              event.runSeq(),
              glEntry.getId());
      row.setCompanyId(companyId);
      claimLedgerRepository.saveAndFlush(row);
    }

    generalLedgerWriter.post(glEntry, companyId);
  }

  /**
   * Re-checks the settle-once guard in a FRESH, independent transaction — used by {@link
   * ExpenseClaimPostingService} to recover the loser of a concurrent settle-once race after its own
   * {@link #settle} transaction aborted with a {@link
   * org.springframework.dao.DataIntegrityViolationException}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isSettledForReplay(UUID claimId) {
    return claimLedgerRepository
        .findByClaimId(claimId)
        .map(EmployeeExpenseClaimLedger::isSettled)
        .orElse(false);
  }
}
