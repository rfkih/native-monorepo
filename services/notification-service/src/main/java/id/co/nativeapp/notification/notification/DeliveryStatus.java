package id.co.nativeapp.notification.notification;

/**
 * The outcome of a delivery attempt, recorded on a {@link DeliveryReceipt}. Matches the {@code
 * delivery_receipt.status} check constraint (DELIVERED | FAILED) in the Flyway baseline, and is the
 * status carried on the published {@code DeliveryReceipt} event.
 */
public enum DeliveryStatus {
  /** The transport accepted the message. */
  DELIVERED,
  /** The transport rejected/failed the message — recorded, never swallowed. */
  FAILED
}
