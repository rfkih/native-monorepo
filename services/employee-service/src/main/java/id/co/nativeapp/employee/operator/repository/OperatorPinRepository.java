package id.co.nativeapp.employee.operator.repository;

import id.co.nativeapp.employee.operator.domain.OperatorPin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link OperatorPin} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). A lookup
 * that resolves to another tenant's operator PIN is invisible under RLS (returns empty), so a
 * cross-tenant read fails closed without any hand-written predicate.
 */
public interface OperatorPinRepository extends JpaRepository<OperatorPin, UUID> {

  /** The operator PIN row for an employee (within the bound tenant), if one has been set. */
  Optional<OperatorPin> findByEmployeeId(UUID employeeId);
}
