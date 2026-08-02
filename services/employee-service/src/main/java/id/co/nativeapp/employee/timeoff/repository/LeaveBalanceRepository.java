package id.co.nativeapp.employee.timeoff.repository;

import id.co.nativeapp.employee.timeoff.domain.LeaveBalance;
import id.co.nativeapp.employee.timeoff.projection.LeaveBalanceRowView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link LeaveBalance} row.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5).
 */
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

  /**
   * The persisted balance row for {@code (employeeId, year)}, if a manager has ever adjusted it.
   */
  Optional<LeaveBalance> findByEmployeeIdAndYear(UUID employeeId, int year);

  /**
   * One row per employee in the tenant for {@code year} — {@code grantedDays}/{@code
   * adjustmentDays} default to {@link LeaveBalance#DEFAULT_GRANTED_DAYS}/0 in SQL (a {@code LEFT
   * JOIN} — most employees never get a persisted row, ADR 0033 §4) alongside a correlated-subquery
   * {@code usedDays}. Ordered by name for a stable manager-facing list.
   */
  @Query(
      value =
          """
          SELECT e.id AS employee_id, e.full_name AS employee_name,
                 COALESCE(lb.granted_days, 12) AS granted_days,
                 COALESCE(lb.adjustment_days, 0) AS adjustment_days,
                 COALESCE((
                   SELECT SUM(lr.days)
                     FROM leave_request lr
                    WHERE lr.employee_id = e.id
                      AND lr.leave_type = 'ANNUAL'
                      AND lr.status = 'APPROVED'
                      AND EXTRACT(YEAR FROM lr.start_date) = :year
                 ), 0) AS used_days
            FROM employee e
            LEFT JOIN leave_balance lb ON lb.employee_id = e.id AND lb.year = :year
           ORDER BY e.full_name
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<LeaveBalanceRowView> findRowsForYear(
      @Param("year") int year, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findRowsForYear} (the envelope's {@code totalElements}). */
  @Query(value = "SELECT COUNT(*) FROM employee", nativeQuery = true)
  long countEmployees();
}
