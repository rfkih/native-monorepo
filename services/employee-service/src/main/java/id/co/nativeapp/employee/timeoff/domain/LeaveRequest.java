package id.co.nativeapp.employee.timeoff.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code leave_request} aggregate — an employee's ANNUAL/UNPAID/SICK leave, from submission to
 * a manager's decision (ADR 0033). It owns its guarded transitions ({@link #approve}, {@link
 * #reject}, {@link #cancel()}); every illegal one throws {@link TimeoffStateException} (→ 409).
 *
 * <p>Unlike {@code ExpenseClaim} there is no DRAFT stage: a request is created directly {@code
 * SUBMITTED} (ADR 0033 §3) — v1 has no draft-editing step, so the overlap guard ({@code
 * LeaveRequestWriter}) can run at creation time against every OTHER submitted/approved request.
 *
 * <p>{@code days} is a WHOLE-day count (v1 — no half-days) supplied by the caller and validated
 * against the inclusive {@code [startDate, endDate]} span only as an upper bound (a caller may
 * request fewer days than the calendar span, e.g. to skip a weekend inside it); the derived {@link
 * LeaveBalance} usage sums this column directly, never re-deriving it from the date range.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code leave_request} RLS policy (rule 5, V12).
 */
@Entity
@Table(name = "leave_request")
public class LeaveRequest extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "leave_type", nullable = false, length = 16, updatable = false)
  private LeaveType leaveType;

  @Column(name = "start_date", nullable = false, updatable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false, updatable = false)
  private LocalDate endDate;

  @Column(name = "days", nullable = false, updatable = false)
  private int days;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private TimeoffStatus status;

  @Column(name = "decided_by", length = 255)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decision_note")
  private String decisionNote;

  /**
   * The client's create-time Idempotency-Key (there is no DRAFT stage — create IS the SUBMIT
   * transition). {@code LeaveRequestRepository#findByIdempotencyKey} is the replay probe {@code
   * LeaveRequestWriter#create} runs BEFORE inserting.
   */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
  private String idempotencyKey;

  protected LeaveRequest() {
    // for JPA
  }

  /**
   * Creates a SUBMITTED leave request with a freshly generated id.
   *
   * @throws IllegalArgumentException if {@code endDate} precedes {@code startDate}, or {@code days}
   *     is not strictly positive, or {@code days} exceeds the inclusive span of the date range (→
   *     400)
   */
  public LeaveRequest(
      UUID employeeId,
      LeaveType leaveType,
      LocalDate startDate,
      LocalDate endDate,
      int days,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.leaveType = Objects.requireNonNull(leaveType, "leaveType");
    this.startDate = Objects.requireNonNull(startDate, "startDate");
    this.endDate = Objects.requireNonNull(endDate, "endDate");
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("endDate " + endDate + " precedes startDate " + startDate);
    }
    if (days <= 0) {
      throw new IllegalArgumentException("days must be strictly positive: " + days);
    }
    long spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
    if (days > spanDays) {
      throw new IllegalArgumentException(
          "days ("
              + days
              + ") exceeds the "
              + spanDays
              + "-day span "
              + startDate
              + ".."
              + endDate);
    }
    this.days = days;
    this.status = TimeoffStatus.SUBMITTED;
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
  }

  /**
   * Transitions {@code SUBMITTED -> APPROVED}, stamping the decision. For {@link LeaveType#ANNUAL}
   * the balance-sufficiency check is enforced by the WRITER (under the per-employee advisory lock),
   * not here — the aggregate alone cannot see the employee's other requests.
   *
   * @throws TimeoffStateException if not SUBMITTED (→ 409)
   */
  public void approve(String actor, String note, Instant decidedAt) {
    requireStatus(TimeoffStatus.SUBMITTED, "approve");
    this.status = TimeoffStatus.APPROVED;
    this.decidedBy = requireNonBlank(actor, "actor");
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    this.decisionNote = blankToNull(note);
  }

  /**
   * Transitions {@code SUBMITTED -> REJECTED}, stamping the decision. A rejection requires a note.
   *
   * @throws TimeoffStateException if not SUBMITTED (→ 409)
   * @throws DecisionCommentRequiredException if {@code note} is blank (→ 422)
   */
  public void reject(String actor, String note, Instant decidedAt) {
    requireStatus(TimeoffStatus.SUBMITTED, "reject");
    if (note == null || note.isBlank()) {
      throw new DecisionCommentRequiredException("leave request", this.id);
    }
    this.status = TimeoffStatus.REJECTED;
    this.decidedBy = requireNonBlank(actor, "actor");
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    this.decisionNote = note.strip();
  }

  /**
   * Transitions {@code SUBMITTED -> CANCELLED} — the employee withdrawing their own request.
   *
   * @throws TimeoffStateException if not SUBMITTED (→ 409)
   */
  public void cancel() {
    requireStatus(TimeoffStatus.SUBMITTED, "cancel");
    this.status = TimeoffStatus.CANCELLED;
  }

  private void requireStatus(TimeoffStatus required, String attempted) {
    if (this.status != required) {
      throw new TimeoffStateException("leave request", this.status, attempted);
    }
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public LeaveType getLeaveType() {
    return leaveType;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public int getDays() {
    return days;
  }

  public TimeoffStatus getStatus() {
    return status;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getDecisionNote() {
    return decisionNote;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @Override
  public String toString() {
    return "LeaveRequest[id=" + id + ", employeeId=" + employeeId + ", status=" + status + "]";
  }
}
