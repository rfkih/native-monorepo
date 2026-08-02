package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema;
import id.co.nativeapp.employee.expense.projection.LinkedClaimIdView;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * ExpenseClaimWriter#payDirect} flush clobber the link (a double payment). The same two UPDATEs
 * also stamp {@code updated_by = 'system:payroll-linker'} explicitly (S1, E5 review) — a native
 * {@code @Modifying} UPDATE never runs through JPA's {@code @LastModifiedBy} auditing listener, so
 * without this the CDC audit trail would show a system-driven release/link with a stale or blank
 * actor.
 *
 * <p><strong>Supersession is SAME-CYCLE, not lagged (W3, E5 review — corrects the original E5
 * design).</strong> {@link #releaseForPeriod} takes the calculating run's OWN {@code run_seq}
 * ({@code currentRunSeq}); its "POSTED but superseded" branch matches a linked run whose {@code
 * run_seq < currentRunSeq} for the same period. Since {@code nextRunSeq} is always {@code max + 1},
 * every PRIOR POSTED run for a period necessarily has a lower run_seq than the run about to be
 * assigned — so "linked to a POSTED run of this period with a lower run_seq" IS exactly "linked to
 * the run I, the calculating run, am about to supersede." The corrective run therefore releases AND
 * re-links a superseded claim to ITSELF, in its own {@code calculate()} cycle — never a third run
 * later. (The original E5 predicate only matched a STRICTLY HIGHER POSTED run_seq, which cannot
 * exist yet at the superseding run's OWN calculate() — it always lagged one cycle. That check is
 * KEPT alongside the new one as an idempotent overlap safety net, never a narrowing.)
 *
 * <p><strong>The non-POSTED release branch is period-agnostic (W2, E5 review — corrects the
 * original E5 design).</strong> {@link #linkForRun} links a claim to a run irrespective of the
 * claim's own {@code expense_date} — an outstanding APPROVED+PAYROLL claim rides ANY future run for
 * that employee. So a claim stranded on an abandoned (CALCULATED-but-never-POSTED) run of period P
 * must be released by ANY later {@code calculate()} for ANY period, not only a re-run of period P —
 * an operator has no reason to ever recalculate an abandoned period on its own. Only the
 * "superseded"/"E5-transitional" branches stay scoped to {@code :period}; the "run not POSTED"
 * branch does not.
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
 *
 * <p><strong>Concurrent {@code calculate()} for two DIFFERENT periods (Track P Phase P7 review
 * S2).</strong> {@link #linkForRun}'s UPDATE is a single conditional statement (row-locked by
 * Postgres), so two overlapping {@code calculate()} transactions racing for the SAME employee's
 * SAME still-{@code APPROVED} claim (e.g. one calculating May, one calculating June, both before
 * either has committed) cannot both win it: whichever transaction's UPDATE acquires the row lock
 * first proceeds; the second BLOCKS until the first commits, then re-evaluates its {@code
 * reimbursement_run_id IS NULL} predicate under READ COMMITTED — now false — and matches zero rows.
 * The claim ends up linked to EXACTLY ONE of the two runs, never both and never neither. A claim
 * MAY therefore "migrate" between two UN-POSTED runs across separate, sequential {@code
 * calculate()} calls (the {@code releaseForPeriod} release/relink dance documented above) — this is
 * expected, not a race bug; the race-safety property is that at most one calculating run holds the
 * link AT ANY INSTANT, proven by {@code ExpenseClaimPayrollLinkerConcurrentPeriodsTest}.
 */
@Component
public class ExpenseClaimPayrollLinker {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimPayrollLinker.class);

  /**
   * The {@code pay_component.component_key} Track P Phase P7 seeds (the {@code ID-2026.2} dataset
   * top-up) as the non-taxable earning that carries a linked claim's reimbursement on the payslip
   * (ADR 0030 §6). P7 IS LIVE: {@code PayrollRunWriter#appendWorkInputs} produces this line
   * whenever the tenant has that catalog component AND a nonzero linked-claim total; for a tenant
   * that has NOT activated the dataset, this key is never produced on any {@code payslip_line}, so
   * {@link #markReimbursedAndEmit}'s gate correctly stays a no-op (the E5-transitional fallback,
   * still load-bearing for that case).
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
   * Releases every claim linked to a stale/superseded run back to {@code APPROVED} + unlinked — see
   * {@link ExpenseClaimRepository#releaseForPeriod} for the exact predicate (incl. the W2
   * period-agnostic non-POSTED branch and the W3 same-cycle supersession fix, both E5 review).
   * Called at the TOP of {@code PayrollRunWriter#calculate}, before {@link #linkForRun}, so a
   * freshly-released claim can be re-linked to the run being calculated in the SAME transaction.
   *
   * @param period the period of the run now calculating
   * @param currentRunSeq the {@code run_seq} about to be assigned to the run now calculating (W3) —
   *     the caller passes its OWN run_seq, so the corrective run's OWN {@code calculate()} is what
   *     releases and re-links a prior POSTED run's claim, not a later third run
   * @return the number of claims released
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int releaseForPeriod(String period, int currentRunSeq) {
    int released = claimRepository.releaseForPeriod(period, currentRunSeq);
    if (released > 0) {
      log.info(
          "expense_claim payroll-linker released {} claim(s) for period {} (run_seq {}"
              + " superseding/stale link)",
          released,
          period,
          currentRunSeq);
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
   * one {@code ExpenseReimbursementSettled(PAYROLL)} outbox row per claim — but ONLY for a claim
   * whose EMPLOYEE actually received an {@link #COMPONENT_KEY_EXPENSE_REIMBURSEMENT} payslip line
   * THIS run (P7 review W4, tightened from the original E5-transitional coarse gate below).
   *
   * <p><strong>W4 — per-claim settle gating.</strong> The ORIGINAL gate only checked whether the
   * run carried the component key ANYWHERE (one employee having a line was enough to unlock
   * settling EVERY linked claim) — but {@code PayrollRunWriter#reimbursementTotalsByEmployee} can
   * SKIP an individual employee (e.g. a currency-mismatched linked total, ADR 0030 §9) while still
   * producing a line for OTHER employees in the same run. Settling that skipped employee's claim
   * would book a payable settlement for money they never actually received via this run — the exact
   * wrong-books failure mode the original gate existed to prevent, just at finer grain. This method
   * now RE- DERIVES, via {@link
   * PayslipLineRepository#findDistinctEmployeeIdsByPayrollRunIdAndComponentKey}, the set of
   * employee ids that actually carry the line THIS run, and only flips/settles a claim whose {@code
   * employee_id} is in that set. A skipped claim stays {@code APPROVED}+linked — {@link
   * ExpenseClaimRepository#releaseForPeriod}'s "POSTED but still APPROVED" branch (the
   * E5-transitional recovery path, unchanged) releases it for the next {@code calculate()} to
   * re-link and retry.
   *
   * <p>Called from {@code PayrollRunWriter#post}, in the SAME CALCULATED→POSTED transaction as the
   * run's other outbox writes — the run's exactly-once posting discipline extends to these emits.
   *
   * @throws IllegalStateException if {@code run} has no {@code posted_at} yet (this method must run
   *     AFTER {@link PayrollRun#markPosted}, never before)
   * @return the number of claims flipped + settled; {@code 0} when NO employee in the run carries
   *     the component (pre-P7 / dataset not activated) or when the run has no linked claims
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

    Set<UUID> employeesWithReimbursementLine =
        new HashSet<>(
            payslipLineRepository.findDistinctEmployeeIdsByPayrollRunIdAndComponentKey(
                run.getId(), COMPONENT_KEY_EXPENSE_REIMBURSEMENT));
    if (employeesWithReimbursementLine.isEmpty()) {
      log.info(
          "expense_claim payroll-linker: run {} (period {}, run_seq {}) carries no {} payslip"
              + " line for ANY employee (E5-transitional, pre-Track-P-Phase-P7 or dataset not"
              + " activated) — any linked claims stay APPROVED+linked for a later re-run's"
              + " release+relink",
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
    int skipped = 0;
    for (ExpenseClaim claim : linked) {
      if (claim.getStatus() != ClaimStatus.APPROVED) {
        // Defensive: post() only calls this once per run (the CALCULATED->POSTED transition is
        // itself one-shot), but never trust a single caller for an idempotency guarantee.
        continue;
      }
      if (!employeesWithReimbursementLine.contains(claim.getEmployeeId())) {
        // W4: this employee's reimbursement was SKIPPED this run (e.g. currency mismatch) — the
        // claim never actually rode a payslip line, so it must NOT be settled. Stays
        // APPROVED+linked; releaseForPeriod's POSTED-but-still-APPROVED branch recovers it.
        skipped++;
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
    if (skipped > 0) {
      log.warn(
          "expense_claim payroll-linker: {} claim(s) linked to run {} (period {}, run_seq {}) had"
              + " NO {} payslip line for their employee this run (W4) — left APPROVED+linked for a"
              + " later re-run",
          skipped,
          run.getId(),
          run.getPeriod(),
          run.getRunSeq(),
          COMPONENT_KEY_EXPENSE_REIMBURSEMENT);
    }
    return settled;
  }

  /**
   * One row per (employee, currency) among the claims currently linked to {@code runId} AND STILL
   * {@code APPROVED} (W1, E5 review) — Track P Phase P7 will read this to build the non-taxable
   * {@code EXPENSE_REIMBURSEMENT} payslip line per employee. See {@link ExpenseClaimRepository
   * #findLinkedClaimTotalsByEmployee} for the {@code APPROVED}-only guard and the currency-grouping
   * rationale.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<LinkedClaimTotalView> findLinkedClaimTotalsByEmployee(UUID runId) {
    return claimRepository.findLinkedClaimTotalsByEmployee(runId);
  }

  /**
   * One (employee, claim) row per claim currently linked to {@code runId} AND STILL {@code
   * APPROVED} (P7 review W3) — {@code PayrollRunWriter} freezes these individual claim ids into
   * {@code work_inputs_json}'s {@code reimbursement.claimIds}, alongside the aggregate total {@link
   * #findLinkedClaimTotalsByEmployee} already provides.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<LinkedClaimIdView> findLinkedClaimIdsByEmployee(UUID runId) {
    return claimRepository.findLinkedClaimIdsByEmployee(runId);
  }
}
