package id.co.nativeapp.employee.assignment.repository;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Whether the employee currently holds an ACTIVE assignment to the given outlet ({@code
   * org_unit_id}, == {@code businessId} in the flat org tree, ADR 0012), effective as of {@code
   * asOf}: {@code effective_from <= asOf AND effective_to >= asOf} (the fleet's standard
   * effective-dated predicate — see {@code employee.repository.EmployeeRepository.findListRows}).
   * The operator-session verify path (ADR 0049 P1) uses this to reject a PIN sign-in for an
   * employee no longer assigned to the outlet they are trying to sign into, even if their PIN and
   * employee record are otherwise valid.
   *
   * <p>An {@code EXISTS} boolean scalar, not a projection — exempt from the read-path projection
   * convention (CODE-STRUCTURE §3.3: {@code count}/{@code exists} return scalars).
   */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1
              FROM assignment a
             WHERE a.employee_id = :employeeId
               AND a.org_unit_id = :outletId
               AND a.effective_from <= :asOf
               AND a.effective_to >= :asOf
          )
          """,
      nativeQuery = true)
  boolean existsActiveAssignment(
      @Param("employeeId") UUID employeeId,
      @Param("outletId") UUID outletId,
      @Param("asOf") LocalDate asOf);
}
