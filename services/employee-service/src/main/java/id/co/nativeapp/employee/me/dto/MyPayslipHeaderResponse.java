package id.co.nativeapp.employee.me.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the caller's payslip index ({@code GET /api/v1/me/payslips}) — a run header only, no
 * amounts (the detail read decrypts the caller's own lines).
 *
 * @param runId the payroll run
 * @param period the run's YYYY-MM period
 * @param runSeq the run sequence within the period (a re-run supersedes earlier ones)
 * @param postedAt when the run posted
 * @param lineCount how many lines the caller has in the run
 * @param illustrative whether the run used illustrative placeholder rules
 */
public record MyPayslipHeaderResponse(
    UUID runId, String period, int runSeq, Instant postedAt, int lineCount, boolean illustrative) {}
