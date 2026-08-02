package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import id.co.nativeapp.finance.labor.domain.PayrollRunState;
import id.co.nativeapp.finance.labor.messaging.PayrollPostedEvent;
import id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that reconciles a consumed {@code PayrollPosted}
 * run-level control event against the running sum of its {@code LaborCostAllocated} buckets,
 * records the run-level illustrative flag, and finalizes the run's state — idempotently (#23).
 *
 * <p><strong>Produces NO ledger posting.</strong> {@code PayrollPosted} is the
 * control/announcement: each bucket already posted its own EXPENSE row ({@link
 * LaborCostPostingWriter}). Here finance only VERIFIES completeness (it does not recompute the
 * allocation — employee-service already asserted the exact-sum invariant before posting).
 *
 * <p><strong>Out-of-order safe.</strong> Buckets and {@code PayrollPosted} arrive on different
 * topics/partitions in any order. If buckets came first, the run row already holds the accumulated
 * sum; if {@code PayrollPosted} comes first, this opens the PENDING row with the control total and
 * a later bucket reconciles by accumulation. Either way the comparison is:
 *
 * <ul>
 *   <li><strong>match</strong> → {@link PayrollRunState#RECONCILED};
 *   <li><strong>mismatch</strong> → {@link PayrollRunState#RECONCILE_FAILED}, logged loudly.
 *       Postings already made stay on the books (money is never torn down); the run is held back
 *       from being presented as a closed/final period. Never silently accept a partial run.
 * </ul>
 *
 * <p>A cross-currency divergence is NOT handled here: the {@code PayrollPosted} totals are all in
 * the run's single {@code base_currency} by construction ({@code PayrollPostedSchema}), and a
 * divergent-currency bucket is caught earlier on the bucket path and routed to the terminal {@link
 * PayrollRunState#CURRENCY_MISMATCH}. {@link Money#equals} returns {@code false} (never throws) on
 * a currency mismatch, so the value-inequality branch is a safe backstop.
 *
 * <p>Idempotency (rule 3): {@code processOnce} + the per-run upsert keyed on {@code (company,
 * period, run_seq)} make a re-delivered {@code PayrollPosted} a harmless re-evaluation.
 */
@Component
public class PayrollReconciliationWriter {

  private static final Logger log = LoggerFactory.getLogger(PayrollReconciliationWriter.class);

  private final PayrollRunLedgerRepository runLedgerRepository;
  private final ProcessedEventStore processedEvents;

  public PayrollReconciliationWriter(
      PayrollRunLedgerRepository runLedgerRepository, ProcessedEventStore processedEvents) {
    this.runLedgerRepository = runLedgerRepository;
    this.processedEvents = processedEvents;
  }

  /**
   * Reconciles the run exactly once per event id. Must be called inside a {@link TenantContext}
   * scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery reconciled (first delivery), {@code false} if skipped as
   *     a re-delivery.
   */
  @Transactional
  public boolean reconcile(PayrollPostedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> reconcileRun(event));
  }

  private void reconcileRun(PayrollPostedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Serialize per (company, period, run_type) with the SAME deterministic advisory lock the
    // bucket path (and PayrollLiabilityWriter) take, BEFORE any run-row read/insert (ADR 0032,
    // Track P phase P4 — was (company, period) pre-P4). This serializes a PayrollPosted against
    // concurrent bucket deliveries (and against a parallel reconcile) so the supersession view
    // every writer observes is consistent — the fix for the first-two-runs double-count (#23).
    runLedgerRepository.lockPeriod(companyId + ":" + event.period() + ":" + event.runType());

    PayrollRunLedger runRow = upsertRunRow(event, companyId);

    // SUPERSEDED is TERMINAL (#23): a higher-run_seq run already reversed and replaced this run. A
    // late PayrollPosted for the old run must NOT re-reconcile it (clobbering SUPERSEDED back to
    // RECONCILED/RECONCILE_FAILED would display a reversed run as reconciled — misleading). Leave
    // it
    // SUPERSEDED and return.
    if (runRow.getState() == PayrollRunState.SUPERSEDED) {
      log.info(
          "PayrollPosted runId={} period={} run_seq={} is already SUPERSEDED (a higher run replaced"
              + " it); leaving it SUPERSEDED — not re-reconciling a reversed run",
          event.payrollRunId(),
          event.period(),
          event.runSeq());
      return;
    }

    // The labor control total = employer-borne base (gross + employer contributions). Both legs of
    // PayrollPosted are decoded in the run's single base_currency (PayrollPostedSchema), so
    // laborControlTotal()'s plus() never mixes currencies — no MismatchedCurrencyException is
    // reachable here. Cross-currency divergence between the run currency and a bucket is caught
    // earlier on the bucket path (LaborCostPostingWriter's divergentPeriodCurrency check, which
    // routes the run to the terminal CURRENCY_MISMATCH); Money.equals below returns false on a
    // currency mismatch (it does NOT throw), so the value-inequality branch covers it safely.
    Money controlTotal = event.laborControlTotal();
    runRow.recordControlTotal(controlTotal, event.usesIllustrativeRules());

    if (runRow.reconcileAgainstControl()) {
      log.info(
          "PayrollPosted runId={} period={} run_seq={} RECONCILED: allocated_sum={} == control={}",
          event.payrollRunId(),
          event.period(),
          event.runSeq(),
          runRow.allocatedSum(),
          controlTotal);
    } else {
      log.error(
          "PayrollPosted runId={} period={} run_seq={} RECONCILE_FAILED: allocated_sum={} !="
              + " control={}; postings stay on the books, the period is held back from final",
          event.payrollRunId(),
          event.period(),
          event.runSeq(),
          runRow.allocatedSum(),
          controlTotal);
    }
    runLedgerRepository.save(runRow);
  }

  /** Finds this run's control row or opens a fresh PENDING one stamped with the event tenant. */
  private PayrollRunLedger upsertRunRow(PayrollPostedEvent event, String companyId) {
    return runLedgerRepository
        .findByPayrollRunIdAndRunSeq(event.payrollRunId(), event.runSeq())
        .orElseGet(
            () -> {
              PayrollRunLedger row =
                  new PayrollRunLedger(
                      event.payrollRunId(),
                      event.period(),
                      event.runSeq(),
                      event.runType(),
                      event.baseCurrency(),
                      event.usesIllustrativeRules());
              row.setCompanyId(companyId);
              return row;
            });
  }
}
