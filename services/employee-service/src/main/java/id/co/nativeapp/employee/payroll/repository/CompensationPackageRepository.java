package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.projection.CompensationView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link CompensationPackage}. No manual {@code WHERE company_id} (RLS scopes
 * every query — rule 5). Base pay is encrypted ciphertext at rest, so it is NOT queryable/sortable
 * by value (rule 6, intentional).
 */
public interface CompensationPackageRepository extends JpaRepository<CompensationPackage, UUID> {

  /** Every compensation package for an employee (within the bound tenant). */
  List<CompensationPackage> findByEmployeeId(UUID employeeId);

  /**
   * The console's masked package list — deliberately does NOT select {@code base_pay_enc}: the
   * salary ciphertext is never read (let alone decrypted) on this path (rule 6).
   */
  @Query(
      value =
          """
          SELECT cp.id                     AS id,
                 cp.employment_contract_id AS employment_contract_id,
                 cp.pay_frequency          AS pay_frequency,
                 cp.effective_from         AS effective_from,
                 cp.effective_to           AS effective_to
            FROM comp_package cp
           WHERE cp.employee_id = :employeeId
           ORDER BY cp.effective_from
          """,
      nativeQuery = true)
  List<CompensationView> findViewsByEmployeeId(@Param("employeeId") UUID employeeId);
}
