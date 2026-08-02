package id.co.nativeapp.employee.expense.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * An append-only audit row for exactly one guarded {@link ExpenseClaim} transition (submit, cancel,
 * approve, refuse, and later void/pay) — ADR 0030 §5, the AP-Bill idempotency idiom. The per-tenant
 * {@code UNIQUE (company_id, claim_id, idempotency_key)} (V9) is BOTH the audit trail of who did
 * what and when, and the replay/concurrency backstop {@code ExpenseClaimWriter} probes before
 * mutating the aggregate and recovers against if a concurrent racer wins the insert.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code expense_claim_event} RLS policy (rule 5,
 * V9).
 */
@Entity
@Table(name = "expense_claim_event")
public class ExpenseClaimEvent extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "action", nullable = false, length = 32)
  private String action;

  @Column(name = "from_status", nullable = false, length = 16)
  private String fromStatus;

  @Column(name = "to_status", nullable = false, length = 16)
  private String toStatus;

  @Column(name = "actor", nullable = false, length = 255)
  private String actor;

  @Column(name = "comment")
  private String comment;

  @Column(name = "idempotency_key", nullable = false, length = 80)
  private String idempotencyKey;

  protected ExpenseClaimEvent() {
    // for JPA
  }

  /** Creates an audit row for one transition, with a freshly generated id. */
  public ExpenseClaimEvent(
      UUID claimId,
      String action,
      ClaimStatus fromStatus,
      ClaimStatus toStatus,
      String actor,
      String comment,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.claimId = Objects.requireNonNull(claimId, "claimId");
    this.action = requireNonBlank(action, "action");
    this.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus").name();
    this.toStatus = Objects.requireNonNull(toStatus, "toStatus").name();
    this.actor = requireNonBlank(actor, "actor");
    this.comment = comment == null || comment.isBlank() ? null : comment.strip();
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
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

  public UUID getClaimId() {
    return claimId;
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

  @Override
  public String toString() {
    return "ExpenseClaimEvent[claimId=" + claimId + ", action=" + action + "]";
  }
}
