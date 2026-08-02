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
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code overtime_entry} aggregate — one worked-overtime record, from submission to a manager's
 * decision (ADR 0033). Same guarded-transition shape as {@link LeaveRequest} ({@code SUBMITTED ->
 * APPROVED | REJECTED}; cancel from {@code SUBMITTED}), sharing {@link TimeoffStatus}/{@link
 * TimeoffStateException}. No overlap or balance guard — multiple overtime entries on the same
 * {@code work_date} are legal (e.g. a split shift); Track P Phase P7 sums {@code minutes} across
 * every APPROVED entry it consumes.
 *
 * <p>{@code minutes} is capped at 600 (10h) by both the {@code chk_overtime_entry_minutes} DB
 * constraint and the constructor — a defensive upper bound on a single day's overtime; the 4h/day
 * regulatory cap validation itself is deferred (statutory spec, Track P Phase P7 note).
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code overtime_entry} RLS policy (rule 5, V12).
 */
@Entity
@Table(name = "overtime_entry")
public class OvertimeEntry extends Auditable {

  /** The DB-enforced upper bound on a single entry's minutes (10 hours). */
  public static final int MAX_MINUTES = 600;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "work_date", nullable = false, updatable = false)
  private LocalDate workDate;

  @Column(name = "minutes", nullable = false, updatable = false)
  private int minutes;

  @Enumerated(EnumType.STRING)
  @Column(name = "day_kind", nullable = false, length = 16, updatable = false)
  private DayKind dayKind;

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
   * The client's create-time Idempotency-Key — see {@code LeaveRequest#idempotencyKey}'s Javadoc
   * for the rationale (identical idiom here).
   */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
  private String idempotencyKey;

  protected OvertimeEntry() {
    // for JPA
  }

  /**
   * Creates a SUBMITTED overtime entry with a freshly generated id.
   *
   * @throws IllegalArgumentException if {@code minutes} is not in {@code (0, MAX_MINUTES]} (→ 400)
   */
  public OvertimeEntry(
      UUID employeeId, LocalDate workDate, int minutes, DayKind dayKind, String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.workDate = Objects.requireNonNull(workDate, "workDate");
    if (minutes <= 0 || minutes > MAX_MINUTES) {
      throw new IllegalArgumentException("minutes must be in (0, " + MAX_MINUTES + "]: " + minutes);
    }
    this.minutes = minutes;
    this.dayKind = Objects.requireNonNull(dayKind, "dayKind");
    this.status = TimeoffStatus.SUBMITTED;
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
  }

  /**
   * Transitions {@code SUBMITTED -> APPROVED}, stamping the decision.
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
      throw new DecisionCommentRequiredException("overtime entry", this.id);
    }
    this.status = TimeoffStatus.REJECTED;
    this.decidedBy = requireNonBlank(actor, "actor");
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    this.decisionNote = note.strip();
  }

  /**
   * Transitions {@code SUBMITTED -> CANCELLED} — the employee withdrawing their own entry.
   *
   * @throws TimeoffStateException if not SUBMITTED (→ 409)
   */
  public void cancel() {
    requireStatus(TimeoffStatus.SUBMITTED, "cancel");
    this.status = TimeoffStatus.CANCELLED;
  }

  private void requireStatus(TimeoffStatus required, String attempted) {
    if (this.status != required) {
      throw new TimeoffStateException("overtime entry", this.status, attempted);
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

  public LocalDate getWorkDate() {
    return workDate;
  }

  public int getMinutes() {
    return minutes;
  }

  public DayKind getDayKind() {
    return dayKind;
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
    return "OvertimeEntry[id=" + id + ", employeeId=" + employeeId + ", status=" + status + "]";
  }
}
