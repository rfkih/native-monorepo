package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema;
import id.co.nativeapp.employee.expense.projection.LinkedClaimTotalView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payroll↔expense-claim seam (ADR 0030 §6, Track E phase E5): links {@code APPROVED} + {@code
 * PAYROLL}-method claims to a calculating run, releases stale/superseded links so a corrective run
 * can re-link them, and (once a real payslip line exists — Track P Phase P7) flips a linked claim
 * to {@code REIMBURSED} and emits ONE {@code ExpenseReimbursementSettled} per claim.
 *
 * <p><strong>Propagation is {@code MANDATORY} on every method — this bean NEVER opens its own
 * transaction.</strong> It is called ONLY from inside {@code PayrollRunWriter#calculate}/{@code
 * #post}, both {@code REQUIRES_NEW} on the Spring proxy, so a linker method invoked outside an
 * active transaction throws {@code IllegalTransactionStateException} rather than silently running
 * unscoped. This is the {@code StockDeductionWriter}/{@code deductForLines} idiom (restaurant
 * -service): the linker's UPDATEs must land on the SAME physical connection/transaction as the
 * run's gate/compute/post so a later failure in that same transaction rolls the link back too.
 *
 * <p><strong>RLS-aspect finding (E5 verification).</strong> {@code RlsAutoApplyAspect}'s pointcut
 * matches every {@code @Transactional} method regardless of propagation, and its delegate ({@code
 * RlsTransactionSynchronizer#applyToCurrentTransaction}) is gated ONLY on {@code
 * TransactionSynchronizationManager.isActualTransactionActive()} — true for a MANDATORY method
 * precisely because it necessarily JOINS an already-active transaction (Spring's tx interceptor
 * rejects the call otherwise). So invoking a linker method re-applies (harmlessly, idempotently)
 * the SAME tenant GUC the caller's own {@code @Transactional} entry already set on that same
 * connection — no separate binding, no gap, no special-casing needed for MANDATORY here.
 *
 * <p><strong>BINDING invariant (ADR 0030 §6).</strong> Every {@link ExpenseClaimRepository} UPDATE
 * here that sets or clears {@code reimbursement_run_id} ({@link ExpenseClaimRepository
 * #releaseForPeriod}, {@link ExpenseClaimRepository#linkForRunChunk}) bumps {@code version} in the
 * SAME statement — see their Javadoc for why a skipped bump lets a stale {@code
 * ExpenseClaimWriter#payDirect} flush clobber the link (a double payment).
 *
 * <p><strong>E5-transitional gating ({@link #markReimbursedAndEmit}).</strong> P7 (a LATER phase)
 * adds the non-taxable {@code EXPENSE_REIMBURSEMENT} payslip line a linked claim rides to actually
 * get paid. Flipping a claim to REIMBURSED and emitting its settlement BEFORE that line exists
 * would settle the books (finance debits the payable) for money the employee never actually
 * received via this run — wrong books. So in E5, {@link #markReimbursedAndEmit} checks whether the
 * run ACTUALLY produced an {@code EXPENSE_REIMBURSEMENT} payslip line; pre-P7 no {@code
 * earning_rule} ever produces one, so the check is always false, the method logs and returns {@code
 * 0}, and every linked claim stays {@code APPROVED} + linked. {@link #releaseForPeriod}'s predicate
 * is written to recover these: linked to a POSTED run whose claim is STILL {@code APPROVED} is
 * released unconditionally, so the NEXT {@code calculate()} for the SAME period (a re-run, e.g.
 * once P7 lands and an operator forces a correction) frees them for re-linking. This is a
 * deliberate, documented transitional design — not a bug — and is mirrored in ADR 0030 §6.
 */
@Component
public class ExpenseClaimPayrollLinker {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimPayrollLinker.class);

  /**
   * The {@code pay_component.component_key} Track P Phase P7 seeds as the non-taxable earning that
   * carries a linked claim's reimbursement on the payslip (ADR 0030 §6). Pre-P7 this key is never
   * produced on any {@code payslip_line}, so {@link #markReimbursedAndEmit}'s gate always no-ops.
   */
  public static final String COMPONENT_KEY_EXPENSE_REIMBURSEMENT = "EXPENSE_REIMBURSEMENT";

  /**
   * IN-clause chunk size (CLAUDE.md: chunk at ≤1000). This codebase carries no Guava dependency (no
   * {@code Lists.partition}), so chunking is a plain {@code subList} loop — the {@code
   * restaurant-service MenuReader}/{@code OrderWriter}/{@code BillWriter} idiom.
   */
  private static final int CHUNK_SIZE = 1000;

  private final ExpenseClaimRepository claimRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final OutboxWriter outboxWriter;

  public ExpenseClaimPayrollLinker(
      ExpenseClaimRepository claimRepository,
      PayslipLineRepository payslipLineRepository,
      OutboxWriter outboxWriter) {
    this.claimRepository = claimRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.outboxWriter = outboxWriter;
  }

  /**
   * Releases every claim linked to a stale/superseded run of {@code period} back to {@code
   * APPROVED} + unlinked — see {@link ExpenseClaimRepository#releaseForPeriod} for the exact
   * predicate. Called at the TOP of {@code PayrollRunWriter#calculate}, before {@link #linkForRun},
   * so a freshly-released claim can be re-linked to the run being calculated in the SAME
   * transaction.
   *
   * @return the number of claims released
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int releaseForPeriod(String period) {
    int released = claimRepository.releaseForPeriod(period);
    if (released > 0) {
      log.info(
          "expense_claim payroll-linker released {} claim(s) for period {} (stale/superseded"
              + " link)",
          released,
          period);
    }
    return released;
  }

  /**
   * Atomically links every un-linked {@code APPROVED} + {@code PAYROLL}-method claim among {@code
   * employeeIds} to {@code runId}, chunked at ≤1000 ids per UPDATE. {@code period} is not part of
   * the UPDATE predicate (an outstanding approved claim rides ANY future payroll run for that
   * employee, regardless of the claim's own {@code expense_date} — Odoo parity); it is carried only
   * for the log line, mirroring {@link #releaseForPeriod}'s call-site symmetry.
   *
   * @return the total number of claims linked across every chunk
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int linkForRun(UUID runId, String period, Collection<UUID> employeeIds) {
    List<UUID> ids = new ArrayList<>(employeeIds);
    int total = 0;
    for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
      List<UUID> chunk = ids.subList(i, Math.min(i + CHUNK_SIZE, ids.size()));
      total += claimRepository.linkForRunChunk(runId, chunk);
    }
    if (total > 0) {
      log.info(
          "expense_claim payroll-linker linked {} claim(s) to run {} (period {})",
          total,
          runId,
          period);
    }
    return total;
  }

  /**
   * Flips every claim linked to {@code run} from {@code APPROVED} to {@code REIMBURSED} and emits
   * one {@code ExpenseReimbursementSettled(PAYROLL)} outbox row per claim — but ONLY if {@code run}
   * actually produced an {@link #COMPONENT_KEY_EXPENSE_REIMBURSEMENT} payslip line (see the class
   * Javadoc's E5-transitional-gating note). Called from {@code PayrollRunWriter#post}, in the SAME
   * CALCULATED→POSTED transaction as the run's other outbox writes — the run's exactly-once posting
   * discipline extends to these emits.
   *
   * @throws IllegalStateException if {@code run} has no {@code posted_at} yet (this method must run
   *     AFTER {@link PayrollRun#markPosted}, never before)
   * @return the number of claims flipped + settled; {@code 0} when the E5-transitional gate is
   *     closed (pre-P7) or when the run has no linked claims
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int markReimbursedAndEmit(PayrollRun run) {
    Instant settledAt = run.getPostedAt();
    if (settledAt == null) {
      throw new IllegalStateException(
          "payroll_run "
              + run.getId()
              + " has no posted_at — markReimbursedAndEmit must run AFTER PayrollRun#markPosted");
    }

    boolean wired =
        payslipLineRepository.existsByPayrollRunIdAndComponentKey(
            run.getId(), COMPONENT_KEY_EXPENSE_REIMBURSEMENT);
    if (!wired) {
      log.info(
          "expense_claim payroll-linker: run {} (period {}, run_seq {}) carries no {} payslip"
              + " line yet (E5-transitional, pre-Track-P-Phase-P7) — any linked claims stay"
              + " APPROVED+linked for a later re-run's release+relink",
          run.getId(),
          run.getPeriod(),
          run.getRunSeq(),
          COMPONENT_KEY_EXPENSE_REIMBURSEMENT);
      return 0;
    }

    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);
    List<ExpenseClaim> linked = claimRepository.findByReimbursementRunId(run.getId());
    int settled = 0;
    for (ExpenseClaim claim : linked) {
      if (claim.getStatus() != ClaimStatus.APPROVED) {
        // Defensive: post() only calls this once per run (the CALCULATED->POSTED transition is
        // itself one-shot), but never trust a single caller for an idempotency guarantee.
        continue;
      }
      claim.settleByPayrollRun(settledAt);
      claimRepository.save(claim);

      GenericRecord record =
          ExpenseReimbursementSettledSchema.toRecordPayroll(
              claim, run.getId(), run.getRunSeq(), settledAt);
      outboxWriter.write(
          ExpenseReimbursementSettledSchema.AGGREGATE_TYPE,
          claim.getId().toString(),
          ExpenseReimbursementSettledSchema.EVENT_TYPE,
          AvroSerde.serialize(record),
          null,
          companyId,
          settledAt);
      settled++;
    }
    if (settled > 0) {
      log.info(
          "expense_claim payroll-linker settled {} claim(s) via payroll run {} (period {},"
              + " run_seq {})",
          settled,
          run.getId(),
          run.getPeriod(),
          run.getRunSeq());
    }
    return settled;
  }

  /**
   * One row per (employee, currency) among the claims currently linked to {@code runId} — Track P
   * Phase P7 will read this to build the non-taxable {@code EXPENSE_REIMBURSEMENT} payslip line per
   * employee. See {@link ExpenseClaimRepository#findLinkedClaimTotalsByEmployee} for the
   * currency-grouping rationale.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<LinkedClaimTotalView> findLinkedClaimTotalsByEmployee(UUID runId) {
    return claimRepository.findLinkedClaimTotalsByEmployee(runId);
  }
}
