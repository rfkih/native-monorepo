package id.co.nativeapp.notification.notification;

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
 * The notification aggregate — the system of record for a notification to be (or that has been)
 * delivered. Created via a TRIGGER event (a consumed {@code ConsolidationClosed}), it carries the
 * channel, the recipient, the rendered subject + body, and a lifecycle {@link NotificationStatus}
 * (PENDING on create, then SENT or FAILED after the delivery attempt).
 *
 * <p>It extends {@link Auditable} (rule 4) and is under the {@code notification} RLS policy (rule
 * 5): the consumer creates it inside a {@link id.co.nativeapp.tenant.TenantContext} scope bound to
 * the trigger event's {@code company_id}, so the RLS {@code WITH CHECK} passes. The {@code
 * triggerEventId} (the consumed event's UUID) is the natural idempotency key — a UNIQUE constraint
 * makes a second notification from a re-delivered trigger impossible at the database level.
 *
 * <p>The aggregate enforces its own invariants (non-null channel/recipient/subject/body/trigger, a
 * non-blank recipient) and exposes no mutation that breaks the lifecycle: {@link #markSent()} /
 * {@link #markFailed()} move PENDING to a terminal status and are idempotent w.r.t. the same
 * target. The {@code recipient} is a delivery destination, never logged at info level.
 */
@Entity
@Table(name = "notification")
public class Notification extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, updatable = false, length = 16)
  private Channel channel;

  @Column(name = "recipient", nullable = false, updatable = false, length = 320)
  private String recipient;

  @Column(name = "subject", nullable = false, updatable = false, length = 255)
  private String subject;

  @Column(name = "body", nullable = false, updatable = false)
  private String body;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private NotificationStatus status;

  @Column(name = "trigger_event_id", nullable = false, updatable = false)
  private UUID triggerEventId;

  protected Notification() {
    // for JPA
  }

  /**
   * Creates a PENDING notification from a trigger.
   *
   * @param channel the delivery channel (EMAIL/PUSH)
   * @param recipient the delivery destination (non-blank)
   * @param subject the rendered subject line
   * @param body the rendered message body
   * @param triggerEventId the UUID of the trigger event that created this notification
   *     (idempotency)
   */
  public Notification(
      Channel channel, String recipient, String subject, String body, UUID triggerEventId) {
    this.id = UUID.randomUUID();
    this.channel = Objects.requireNonNull(channel, "channel");
    this.recipient = requireNonBlank(recipient, "recipient");
    this.subject = Objects.requireNonNull(subject, "subject");
    this.body = Objects.requireNonNull(body, "body");
    this.triggerEventId = Objects.requireNonNull(triggerEventId, "triggerEventId");
    this.status = NotificationStatus.PENDING;
  }

  /** Marks this notification SENT after a successful delivery. */
  public void markSent() {
    this.status = NotificationStatus.SENT;
  }

  /** Marks this notification FAILED after a failed delivery attempt (recorded, not swallowed). */
  public void markFailed() {
    this.status = NotificationStatus.FAILED;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public UUID getId() {
    return id;
  }

  public Channel getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public NotificationStatus getStatus() {
    return status;
  }

  public UUID getTriggerEventId() {
    return triggerEventId;
  }
}
