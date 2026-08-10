package id.co.nativeapp.employee.employee.repository;

import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.projection.EmploymentTypeAsOfView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link EmploymentContract}.
 *
 * <p>A thin data port: derived/native queries only, no business logic, no manual {@code WHERE
 * company_id} — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). {@code
 * findByEmployeeId} filters by employee <em>within</em> the bound tenant; RLS adds the {@code
 * company_id} predicate, so it can only ever return contracts of the bound tenant.
 */
public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, UUID> {

  /** Every contract for an employee (within the bound tenant). */
  List<EmploymentContract> findByEmployeeId(UUID employeeId);

  /**
   * The effective {@code employment_type} per employee as-of {@code asOf}, scoped to {@code
   * employeeIds} (within the bound tenant) — the payroll run's P0 scope-gate read (ADR 0055 §5).
   * Native + projection (CODE-STRUCTURE §3.3), selecting only the two columns the gate needs. An
   * employee with no contract covering {@code asOf} simply has no row here — the caller ({@code
   * PayrollRunWriter#requireSupportedEmploymentTypes}) treats that as FAIL-CLOSED (rejects the run),
   * never as an implicit "assume pegawai tetap". {@code employeeIds} must already be chunked by the
   * caller (CLAUDE.md, {@code Lists.partition} at &le;1000).
   */
  @Query(
      value =
          """
          SELECT ec.employee_id     AS employee_id,
                 ec.employment_type AS employment_type
            FROM employment_contract ec
           WHERE ec.employee_id IN (:employeeIds)
             AND ec.effective_from <= :asOf
             AND ec.effective_to   >= :asOf
          """,
      nativeQuery = true)
  List<EmploymentTypeAsOfView> findEffectiveTypesAsOf(
      @Param("employeeIds") List<UUID> employeeIds, @Param("asOf") LocalDate asOf);
}
