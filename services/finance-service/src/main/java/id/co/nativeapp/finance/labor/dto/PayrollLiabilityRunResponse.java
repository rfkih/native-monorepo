package id.co.nativeapp.finance.labor.dto;

import java.util.List;
import java.util.UUID;

/**
 * One ACTIVE payroll run's liability recognition + settlement status (ADR 0032, Track P phase P5) —
 * {@code GET /api/v1/payroll-liabilities?period=} response row. Always carries all five {@link
 * PayrollLiabilityBucketResponse} kinds (a bucket the run never recognised reads as zero minor —
 * the console renders it, but the settle action is disabled both client- and server-side).
 *
 * @param runLedgerId the {@code payroll_run_ledger} row id (the settlement path variable)
 * @param payrollRunId the owning payroll run id (employee-service's id)
 * @param period the run's accounting period {@code YYYY-MM}
 * @param runSeq the run sequence
 * @param runType the payroll run type ({@code REGULAR} today, ADR 0032)
 * @param currency the run's ISO-4217 currency
 * @param buckets all five liability buckets, in {@code NET_WAGES, PPH21, BPJS_KES, BPJS_TK, OTHER}
 *     order
 */
public record PayrollLiabilityRunResponse(
    UUID runLedgerId,
    UUID payrollRunId,
    String period,
    int runSeq,
    String runType,
    String currency,
    List<PayrollLiabilityBucketResponse> buckets) {}
