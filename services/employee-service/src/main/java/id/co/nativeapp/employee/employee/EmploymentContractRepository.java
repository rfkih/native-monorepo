package id.co.nativeapp.employee.employee;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link EmploymentContract}.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). {@code findByEmployeeId}
 * filters by employee <em>within</em> the bound tenant; RLS adds the {@code company_id} predicate,
 * so it can only ever return contracts of the bound tenant.
 */
public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, UUID> {

  /** Every contract for an employee (within the bound tenant). */
  List<EmploymentContract> findByEmployeeId(UUID employeeId);
}
