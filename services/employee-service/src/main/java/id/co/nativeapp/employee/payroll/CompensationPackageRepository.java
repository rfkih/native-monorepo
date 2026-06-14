package id.co.nativeapp.employee.payroll;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link CompensationPackage}. Derived queries only; no manual {@code WHERE
 * company_id} (RLS scopes every query — rule 5). Base pay is encrypted ciphertext at rest, so it is
 * NOT queryable/sortable by value (rule 6, intentional).
 */
public interface CompensationPackageRepository extends JpaRepository<CompensationPackage, UUID> {

  /** Every compensation package for an employee (within the bound tenant). */
  List<CompensationPackage> findByEmployeeId(UUID employeeId);
}
