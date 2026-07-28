package id.co.nativeapp.employee.payroll.dto;

import java.util.UUID;

/**
 * One aggregated labor-cost bucket of a run ({@code GET /api/v1/payroll-runs/{id}/allocations}) —
 * summed per (outlet, GL account) across employees. The aggregation IS the privacy guard: the
 * underlying rows are per-employee, and exposing them would leak individual salary (rule 6), so
 * this DTO deliberately carries no employee reference.
 *
 * @param outletOrgUnitId the outlet bucket (the all-zeros sentinel for the UNALLOCATED bucket)
 * @param glAccount the GL account the bucket posts to
 * @param amountMinor the summed amount in minor units
 * @param currency the ISO-4217 currency
 * @param unallocated whether this is the UNALLOCATED suspense bucket (no current assignment)
 */
public record AllocationSummaryResponse(
    UUID outletOrgUnitId,
    String glAccount,
    long amountMinor,
    String currency,
    boolean unallocated) {}
