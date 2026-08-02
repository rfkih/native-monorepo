package id.co.nativeapp.employee.timeoff.repository;

import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.projection.MyOvertimeEntryView;
import id.co.nativeapp.employee.timeoff.projection.OvertimeEntrySummaryView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link OvertimeEntry} aggregate. Same shape as {@code
 * LeaveRequestRepository} minus the overlap/balance probes — overtime entries carry no such guard
 * (ADR 0033 §4).
 */
public interface OvertimeEntryRepository extends JpaRepository<OvertimeEntry, UUID> {

  /**
   * The entry with this exact create-time Idempotency-Key, if one was already created in this
   * tenant — see {@code LeaveRequestRepository#findByIdempotencyKey}'s Javadoc for the rationale.
   */
  java.util.Optional<OvertimeEntry> findByIdempotencyKey(String idempotencyKey);

  /** One page of the caller's own entries, newest-updated first. */
  @Query(
      value =
          """
          SELECT id, work_date, minutes, day_kind, status, decided_by, decided_at, decision_note
            FROM overtime_entry
           WHERE employee_id = :employeeId
           ORDER BY updated_at DESC
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<MyOvertimeEntryView> findMyEntries(
      @Param("employeeId") UUID employeeId, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findMyEntries} (the envelope's {@code totalElements}). */
  @Query(
      value = "SELECT COUNT(*) FROM overtime_entry WHERE employee_id = :employeeId",
      nativeQuery = true)
  long countMyEntries(@Param("employeeId") UUID employeeId);

  /**
   * One page of the manager-facing tenant-wide entry list: optional status filter, SUBMITTED-first.
   */
  @Query(
      value =
          """
          SELECT oe.id AS id, oe.employee_id AS employee_id, e.full_name AS employee_name,
                 oe.work_date AS work_date, oe.minutes AS minutes, oe.day_kind AS day_kind,
                 oe.status AS status, oe.decided_by AS decided_by, oe.decided_at AS decided_at,
                 oe.decision_note AS decision_note
            FROM overtime_entry oe
            JOIN employee e ON e.id = oe.employee_id
           WHERE (CAST(:status AS text) IS NULL OR oe.status = CAST(:status AS text))
           ORDER BY (CASE WHEN oe.status = 'SUBMITTED' THEN 0 ELSE 1 END), oe.updated_at DESC
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<OvertimeEntrySummaryView> findForManager(
      @Param("status") String status, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findForManager} (the envelope's {@code totalElements}). */
  @Query(
      value =
          "SELECT COUNT(*) FROM overtime_entry"
              + " WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))",
      nativeQuery = true)
  long countForManager(@Param("status") String status);
}
