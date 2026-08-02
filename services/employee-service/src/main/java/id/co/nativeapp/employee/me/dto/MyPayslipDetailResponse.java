package id.co.nativeapp.employee.me.dto;

import java.util.List;
import java.util.UUID;

/**
 * The caller's OWN payslip for one run ({@code GET /api/v1/me/payslips/{runId}}) — the first and
 * only consumer of the authorized (decrypted) payslip read: these are the caller's own salary
 * figures, resolved strictly via the employee↔login link, never a request parameter.
 *
 * <p>Totals are employee-borne: {@code grossMinor} = employee EARNING lines, {@code deductionMinor}
 * = employee DEDUCTION lines, {@code netMinor} = gross − deductions. Employer-borne lines appear in
 * {@code lines} (transparency) but do not move the totals.
 *
 * @param runId the payroll run
 * @param period the run's YYYY-MM period
 * @param runSeq the run sequence within the period
 * @param runType REGULAR | THR (Track P phase P9, the printable payslip's run-type badge)
 * @param currency the ISO-4217 currency of every amount
 * @param grossMinor the caller's gross earnings in minor units
 * @param deductionMinor the caller's total deductions in minor units
 * @param netMinor the caller's net pay in minor units
 * @param illustrative whether the run used illustrative placeholder rules
 * @param lines the caller's payslip lines with REAL amounts
 */
public record MyPayslipDetailResponse(
    UUID runId,
    String period,
    int runSeq,
    String runType,
    String currency,
    long grossMinor,
    long deductionMinor,
    long netMinor,
    boolean illustrative,
    List<MyPayslipLineResponse> lines) {}
