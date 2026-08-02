package id.co.nativeapp.employee.payroll.dto;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a {@link PayrollRun} — company-level aggregates only (NOT individual PII, safe
 * to expose). Carries the run id, period, run_seq, status, base currency, the four company TOTALS
 * (as minor units + currency), the {@code usesIllustrativeRules} flag so a caller can visibly mark
 * an illustrative run, and the FROZEN per-employee work-input breakdown (Track P Phase P7 — the raw
 * {@code work_inputs_json}, {@code "{}"} for a pre-P7 run or one that consumed nothing; carries
 * only ids/day-counts/minute-counts/reimbursement totals, never salary, rule 6).
 */
public record PayrollRunResponse(
    UUID id,
    String period,
    int runSeq,
    String status,
    String baseCurrency,
    long grossTotalMinor,
    long employeeDeductionTotalMinor,
    long employerContributionTotalMinor,
    long netTotalMinor,
    boolean usesIllustrativeRules,
    Instant postedAt,
    String workInputsJson) {

  /** Maps a run aggregate to its response DTO. */
  public static PayrollRunResponse from(PayrollRun run) {
    return new PayrollRunResponse(
        run.getId(),
        run.getPeriod(),
        run.getRunSeq(),
        run.getStatus().name(),
        run.getBaseCurrency(),
        run.getGrossTotal().amountMinor(),
        run.getEmployeeDeductionTotal().amountMinor(),
        run.getEmployerContributionTotal().amountMinor(),
        run.getNetTotal().amountMinor(),
        run.usesIllustrativeRules(),
        run.getPostedAt(),
        run.getWorkInputsJson());
  }
}
