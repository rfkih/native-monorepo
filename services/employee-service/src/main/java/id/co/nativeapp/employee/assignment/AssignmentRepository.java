package id.co.nativeapp.employee.assignment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link Assignment} aggregate.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). {@code findByEmployeeId}
 * powers the same-legal-employer concurrency check and the GET-with-assignments read; RLS scopes
 * both to the bound tenant, so they can only ever see the bound tenant's assignments.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

  /** Every assignment for an employee (within the bound tenant). */
  List<Assignment> findByEmployeeId(UUID employeeId);
}
