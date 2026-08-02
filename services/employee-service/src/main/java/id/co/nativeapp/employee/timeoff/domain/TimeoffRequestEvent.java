package id.co.nativeapp.employee.timeoff.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code timeoff_request_event} append-only audit + idempotency-replay row — ONE shared table
 * for both {@link LeaveRequest} and {@link OvertimeEntry} transitions, discriminated by {@link
 * RequestKind} (ADR 0033 §5, mirroring {@code expense_claim_event}, V9). Every guarded transition
 * (create-as-submit/approve/reject/cancel) appends exactly one row keyed by {@code (company_id,
 * request_kind, request_id, idempotency_key)} — the UNIQUE index is both the audit trail and the
 * concurrency backstop a retried/racing request recovers against.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code timeoff_request_event} RLS policy (rule
 * 5, V12).
 */
@Entity
@Table(name = "timeoff_request_event")
public class TimeoffRequestEvent extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_kind", nullable = false, length = 16, updatable = false)
  private RequestKind requestKind;

  @Column(name = "request_id", nullable = false, updatable = false)
  private UUID requestId;

  @Column(name = "action", nullable = false, length = 32, updatable = false)
  private String action;

  @Column(name = "from_status", nullable = false, length = 16, updatable = false)
  private String fromStatus;

  @Column(name = "to_status", nullable = false, length = 16, updatable = false)
  private String toStatus;

  @Column(name = "actor", nullable = false, updatable = false)
  private String actor;

  @Column(name = "comment", updatable = false)
  private String comment;

  @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
  private String idempotencyKey;

  protected TimeoffRequestEvent() {
    // for JPA
  }

  public TimeoffRequestEvent(
      RequestKind requestKind,
      UUID requestId,
      String action,
      TimeoffStatus fromStatus,
      TimeoffStatus toStatus,
      String actor,
      String comment,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.requestKind = Objects.requireNonNull(requestKind, "requestKind");
    this.requestId = Objects.requireNonNull(requestId, "requestId");
    this.action = Objects.requireNonNull(action, "action");
    this.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus").name();
    this.toStatus = Objects.requireNonNull(toStatus, "toStatus").name();
    this.actor = Objects.requireNonNull(actor, "actor");
    this.comment = comment;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  public UUID getId() {
    return id;
  }

  public RequestKind getRequestKind() {
    return requestKind;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public String getAction() {
    return action;
  }

  public String getFromStatus() {
    return fromStatus;
  }

  public String getToStatus() {
    return toStatus;
  }

  public String getActor() {
    return actor;
  }

  public String getComment() {
    return comment;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
