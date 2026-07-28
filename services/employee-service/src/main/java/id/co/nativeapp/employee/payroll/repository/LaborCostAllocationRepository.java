package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.LaborCostAllocation;
import id.co.nativeapp.employee.payroll.projection.AllocationSummaryView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link LaborCostAllocation}. RLS-scoped (rule 5). Allocation amounts are
 * non-PII cost-center aggregates (queryable) — but the ROWS are per-employee, so any read that
 * leaves this service must go through the aggregated summary query, never row-by-row.
 */
public interface LaborCostAllocationRepository extends JpaRepository<LaborCostAllocation, UUID> {

  /** Every allocation row for a run (within the bound tenant). */
  List<LaborCostAllocation> findByPayrollRunId(UUID payrollRunId);

  /**
   * A run's labor-cost buckets SUMmed per (outlet, GL account, currency) across employees — the
   * aggregation is the privacy guard (rule 6: per-employee rows would leak individual salary).
   * {@code unallocated} mirrors the event derivation: the all-zeros sentinel outlet.
   */
  @Query(
      value =
          """
          SELECT lca.outlet_org_unit_id      AS outlet_org_unit_id,
                 lca.gl_account              AS gl_account,
                 SUM(lca.amount_minor)       AS amount_minor,
                 lca.amount_currency         AS currency,
                 (lca.outlet_org_unit_id = '00000000-0000-0000-0000-000000000000'::uuid)
                                             AS unallocated
            FROM labor_cost_allocation lca
           WHERE lca.payroll_run_id = :runId
           GROUP BY lca.outlet_org_unit_id, lca.gl_account, lca.amount_currency
           ORDER BY unallocated, lca.gl_account, lca.outlet_org_unit_id
          """,
      nativeQuery = true)
  List<AllocationSummaryView> findAllocationSummaries(@Param("runId") UUID runId);
}
