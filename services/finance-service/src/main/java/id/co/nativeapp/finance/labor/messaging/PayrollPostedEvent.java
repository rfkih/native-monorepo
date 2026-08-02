package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code PayrollPosted} event (#23) — the run-level control/announcement. Produces NO
 * ledger posting; finance uses it for reconciliation (the sum of the run's {@code
 * LaborCostAllocated} buckets must equal the labor control total), the run-level illustrative flag,
 * and the supersession trigger / run-state machine.
 *
 * <p>The labor CONTROL TOTAL the per-bucket sum is reconciled against is {@code
 * employerContributionTotal + grossTotal} (the employer-borne labor base) — see {@link
 * PayrollReconciliationService}. Totals are company-level only (no PII — rule 6). All money is
 * {@link Money} (never a float, rule 8). {@code ruleVersions} is decoded for audit but not
 * persisted in the ledger.
 *
 * @param eventId the source event UUID (idempotency key)
 * @param companyId the owning tenant the consumer binds the handler to
 * @param payrollRunId the owning payroll run
 * @param runSeq the run sequence (the supersession signal, scoped within runType)
 * @param runType the payroll run type ({@code REGULAR} today; {@code THR} lands at Track P phase
 *     P8, ADR 0034 — added backward-compatibly with a {@code REGULAR} default, ADR 0032 Track P
 *     phase P4)
 * @param period the run's accounting period {@code YYYY-MM}
 * @param baseCurrency the ISO-4217 base currency the totals are denominated in
 * @param grossTotal the company-level gross total
 * @param employeeDeductionTotal the company-level employee-deduction total
 * @param employerContributionTotal the company-level employer-contribution total
 * @param netTotal the company-level net total
 * @param usesIllustrativeRules whether the run was computed against illustrative-placeholder
 *     figures
 * @param postedAt when the run posted (the fallback supersession ordering if run_seq is absent)
 */
public record PayrollPostedEvent(
    UUID eventId,
    String companyId,
    UUID payrollRunId,
    int runSeq,
    String runType,
    String period,
    String baseCurrency,
    Money grossTotal,
    Money employeeDeductionTotal,
    Money employerContributionTotal,
    Money netTotal,
    boolean usesIllustrativeRules,
    Instant postedAt) {

  /**
   * The labor control total the sum of this run's {@code LaborCostAllocated} bucket amounts must
   * equal: the employer-borne labor base = {@code grossTotal + employerContributionTotal}, in minor
   * units, same currency. Mirrors the producer's exact-sum allocation invariant (each person's
   * total labor cost = gross + employer legs), so finance VERIFIES rather than recomputes.
   *
   * @throws id.co.nativeapp.money.MismatchedCurrencyException if the two totals are in different
   *     currencies (a producer-side inconsistency — caught by reconciliation, not DLT-looped)
   */
  public Money laborControlTotal() {
    return grossTotal.plus(employerContributionTotal);
  }
}
