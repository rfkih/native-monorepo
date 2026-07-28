package id.co.nativeapp.employee.payroll.projection;

import java.util.UUID;

/**
 * Read projection for a run's aggregated labor-cost buckets — SUMmed per (outlet, GL account,
 * currency) across employees. The aggregation is the privacy guard (rule 6): the underlying {@code
 * labor_cost_allocation} rows are per-employee and must never reach a response row-by-row.
 * Snake_case native-query aliases map to these accessors (CODE-STRUCTURE §3.3).
 */
public interface AllocationSummaryView {

  UUID getOutletOrgUnitId();

  String getGlAccount();

  Long getAmountMinor();

  String getCurrency();

  Boolean getUnallocated();
}
