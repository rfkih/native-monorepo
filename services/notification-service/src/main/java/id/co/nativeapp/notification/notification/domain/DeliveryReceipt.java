package id.co.nativeapp.notification.notification.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The delivery receipt for a notification — one terminal record of a delivery attempt's outcome:
 * the {@link DeliveryStatus} (DELIVERED / FAILED), the transport's {@code providerRef} (a synthetic
 * id for the stub, or null when the transport returned none), and when the attempt completed. A
 * delivery FAILURE is RECORDED here (a FAILED receipt), never swallowed.
 *
 * <p>It extends {@link Auditable} (rule 4) and is under the {@code delivery_receipt} RLS policy
 * (rule 5): it is written in the same tenant-scoped transaction as the notification it reports, so
 * the RLS {@code WITH CHECK} passes. A UNIQUE constraint on {@code notification_id} enforces
 * exactly one terminal receipt per notification in this slice (deliver-once) — the database
 * backstop behind the idempotent consumer.
 */
@Entity
@Table(name = "delivery_receipt")
public class DeliveryReceipt extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "notification_id", nullable = false, updatable = false)
  private UUID notificationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, updatable = false, length = 16)
  private DeliveryStatus status;

  @Column(name = "provider_ref", updatable = false, length = 255)
  private String providerRef;

  @Column(name = "delivered_at", nullable = false, updatable = false)
  private Instant deliveredAt;

  protected DeliveryReceipt() {
    // for JPA
  }

  /**
   * Creates a delivery receipt.
   *
   * @param notificationId the notification this receipt is for
   * @param status the delivery outcome (DELIVERED / FAILED)
   * @param providerRef the transport reference (synthetic for the stub), or null
   * @param deliveredAt when the delivery attempt completed (UTC)
   */
  public DeliveryReceipt(
      UUID notificationId, DeliveryStatus status, String providerRef, Instant deliveredAt) {
    this.id = UUID.randomUUID();
    this.notificationId = Objects.requireNonNull(notificationId, "notificationId");
    this.status = Objects.requireNonNull(status, "status");
    this.providerRef = providerRef;
    this.deliveredAt = Objects.requireNonNull(deliveredAt, "deliveredAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getNotificationId() {
    return notificationId;
  }

  public DeliveryStatus getStatus() {
    return status;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }
}
