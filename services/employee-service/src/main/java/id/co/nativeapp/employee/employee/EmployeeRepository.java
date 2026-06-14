package id.co.nativeapp.employee.employee;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link Employee} aggregate.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC on every {@code @Transactional}
 * method (rule 5). A lookup that resolves to another tenant's employee is invisible under RLS
 * (returns empty), so a cross-tenant read fails closed without any hand-written predicate.
 */
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {}
