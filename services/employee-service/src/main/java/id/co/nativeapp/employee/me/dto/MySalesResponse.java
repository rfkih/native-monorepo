package id.co.nativeapp.employee.me.dto;

/**
 * The caller's own sales + commission preview for a period ({@code GET /api/v1/me/sales}). The
 * sales figure and the commission ESTIMATE are non-PII operational aggregates of the caller's own
 * activity; the estimate is a preview — the posted payslip is authoritative.
 *
 * @param period the YYYY-MM period
 * @param salesMinor the caller's own summed sales for the period (minor units)
 * @param currency the ISO-4217 currency
 * @param commissionBasisPoints the caller's active commission rate (basis points), or null if none
 * @param commissionEstimateMinor the estimated commission (rate × sales), or null if no rate
 */
public record MySalesResponse(
    String period,
    long salesMinor,
    String currency,
    Integer commissionBasisPoints,
    Long commissionEstimateMinor) {}
