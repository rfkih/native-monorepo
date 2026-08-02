package id.co.nativeapp.employee.timeoff.repository;

import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.projection.LeaveRequestSummaryView;
import id.co.nativeapp.employee.timeoff.projection.MyLeaveRequestView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link LeaveRequest} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). The full entity is loaded only on the write path
 * via the inherited {@code findById}/{@code save}; every read here is a native-query projection
 * selecting only the columns the caller needs (CODE-STRUCTURE §3.3).
 */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

  /**
   * The request with this exact create-time Idempotency-Key, if one was already created in this
   * tenant — the replay probe {@code LeaveRequestWriter#create} runs BEFORE inserting (the {@code
   * fixed_asset} "acquire" idiom, V34). Loads the full entity: this is itself the write path's
   * pre-check, not a display read (CODE-STRUCTURE §3.3).
   */
  java.util.Optional<LeaveRequest> findByIdempotencyKey(String idempotencyKey);

  /** One page of the caller's own requests, newest-updated first. */
  @Query(
      value =
          """
          SELECT id, leave_type, start_date, end_date, days, status,
                 decided_by, decided_at, decision_note
            FROM leave_request
           WHERE employee_id = :employeeId
           ORDER BY updated_at DESC
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<MyLeaveRequestView> findMyRequests(
      @Param("employeeId") UUID employeeId, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findMyRequests} (the envelope's {@code totalElements}). */
  @Query(
      value = "SELECT COUNT(*) FROM leave_request WHERE employee_id = :employeeId",
      nativeQuery = true)
  long countMyRequests(@Param("employeeId") UUID employeeId);

  /**
   * One page of the manager-facing tenant-wide request list: optional status filter. Ordered
   * SUBMITTED-first (pending work can never fall off the end of a page), then {@code updated_at}
   * DESC within each group — the expense-claim idiom.
   */
  @Query(
      value =
          """
          SELECT lr.id AS id, lr.employee_id AS employee_id, e.full_name AS employee_name,
                 lr.leave_type AS leave_type, lr.start_date AS start_date, lr.end_date AS end_date,
                 lr.days AS days, lr.status AS status, lr.decided_by AS decided_by,
                 lr.decided_at AS decided_at, lr.decision_note AS decision_note
            FROM leave_request lr
            JOIN employee e ON e.id = lr.employee_id
           WHERE (CAST(:status AS text) IS NULL OR lr.status = CAST(:status AS text))
           ORDER BY (CASE WHEN lr.status = 'SUBMITTED' THEN 0 ELSE 1 END), lr.updated_at DESC
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<LeaveRequestSummaryView> findForManager(
      @Param("status") String status, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findForManager} (the envelope's {@code totalElements}). */
  @Query(
      value =
          "SELECT COUNT(*) FROM leave_request"
              + " WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))",
      nativeQuery = true)
  long countForManager(@Param("status") String status);

  /**
   * Whether ANY OTHER SUBMITTED/APPROVED leave request for {@code employeeId} overlaps the
   * inclusive date range {@code [startDate, endDate]} (ADR 0033 §4) — the overlap-guard probe
   * {@code LeaveRequestWriter} runs UNDER the per-employee advisory lock.
   */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1
              FROM leave_request
             WHERE employee_id = :employeeId
               AND status IN ('SUBMITTED', 'APPROVED')
               AND start_date <= :endDate
               AND end_date   >= :startDate
          )
          """,
      nativeQuery = true)
  boolean existsOverlapping(
      @Param("employeeId") UUID employeeId,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate);

  /**
   * The SUM of {@code days} across every APPROVED {@code ANNUAL} request for {@code employeeId}
   * whose {@code start_date} falls in {@code year} — the derived leave-balance USAGE both {@code
   * LeaveBalanceReader} (read) and {@code LeaveRequestWriter#approve} (the balance-sufficiency
   * guard, under the same advisory lock) compute from. {@code COALESCE} to 0 for no rows.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(days), 0)
            FROM leave_request
           WHERE employee_id = :employeeId
             AND leave_type = 'ANNUAL'
             AND status = 'APPROVED'
             AND EXTRACT(YEAR FROM start_date) = :year
          """,
      nativeQuery = true)
  int sumApprovedAnnualDaysForYear(@Param("employeeId") UUID employeeId, @Param("year") int year);

  /**
   * Takes a DETERMINISTIC per-employee transaction-scoped advisory lock — serializes concurrent
   * overlap-check-then-insert and balance-check-then-approve attempts for the SAME employee (the
   * {@code expense_claim}'s currency-establishment idiom, W1 code review there). Transaction-scoped
   * (auto-released at commit/rollback, no manual unlock); the key carries a distinct namespace
   * prefix so it can never collide with a differently-namespaced lock keyed on the same {@code
   * employee_id} (ADR 0033 §4).
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockEmployeeTimeoff(@Param("key") String key);
}
