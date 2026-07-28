package id.co.nativeapp.employee.employee.repository;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.projection.EmployeeListView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link Employee} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). A lookup
 * that resolves to another tenant's employee is invisible under RLS (returns empty), so a
 * cross-tenant read fails closed without any hand-written predicate.
 */
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

  /** The employee linked to a console login (within the bound tenant), if any. */
  java.util.Optional<Employee> findByUserId(String userId);

  /**
   * The console HR list: one row per (employee × CURRENT assignment in scope). {@code LEFT JOIN}
   * so, when unscoped ({@code hasUnits = false}), employees without a current assignment still
   * appear with null assignment columns; when scoped, the {@code IN} predicate on the joined column
   * makes the join effectively inner. {@code has_compensation} is an EXISTS probe — the encrypted
   * {@code base_pay_enc} is never selected (rule 6). Nullable text params go through {@code CAST}
   * so PostgreSQL can type the placeholder. No {@code WHERE company_id} — RLS scopes {@code
   * employee}, {@code assignment}, and {@code comp_package} alike (rule 5).
   */
  @Query(
      value =
          """
          SELECT e.id             AS employee_id,
                 e.full_name      AS full_name,
                 e.status         AS status,
                 e.ptkp_status    AS ptkp_status,
                 e.user_id        AS user_id,
                 a.id             AS assignment_id,
                 a.org_unit_id    AS org_unit_id,
                 a.role           AS role,
                 a.reporting_to   AS reporting_to,
                 a.effective_from AS effective_from,
                 a.effective_to   AS effective_to,
                 EXISTS (SELECT 1
                           FROM comp_package cp
                          WHERE cp.employee_id = e.id
                            AND cp.effective_from <= :asOf
                            AND cp.effective_to >= :asOf) AS has_compensation
            FROM employee e
            LEFT JOIN assignment a
              ON a.employee_id = e.id
             AND a.effective_from <= :asOf
             AND a.effective_to >= :asOf
           WHERE (:hasUnits = FALSE OR a.org_unit_id IN (:orgUnitIds))
             AND (CAST(:status AS text) IS NULL OR e.status = CAST(:status AS text))
             AND (CAST(:q AS text) IS NULL
                  OR e.full_name ILIKE '%' || CAST(:q AS text) || '%')
           ORDER BY e.full_name, a.effective_from
          """,
      nativeQuery = true)
  List<EmployeeListView> findListRows(
      @Param("orgUnitIds") List<UUID> orgUnitIds,
      @Param("hasUnits") boolean hasUnits,
      @Param("status") String status,
      @Param("q") String q,
      @Param("asOf") LocalDate asOf);
}
