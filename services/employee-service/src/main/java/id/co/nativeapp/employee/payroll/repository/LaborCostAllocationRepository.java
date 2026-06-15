package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.LaborCostAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link LaborCostAllocation}. Derived queries only; RLS-scoped (rule 5).
 * Allocation amounts are non-PII cost-center aggregates (queryable).
 */
public interface LaborCostAllocationRepository extends JpaRepository<LaborCostAllocation, UUID> {

  /** Every allocation row for a run (within the bound tenant). */
  List<LaborCostAllocation> findByPayrollRunId(UUID payrollRunId);
}
