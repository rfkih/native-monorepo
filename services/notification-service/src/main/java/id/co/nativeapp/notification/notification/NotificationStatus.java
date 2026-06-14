package id.co.nativeapp.notification.notification;

/**
 * The lifecycle status of a notification. Matches the {@code notification.status} check constraint
 * (PENDING | SENT | FAILED) in the Flyway baseline.
 */
public enum NotificationStatus {
  /** Created, not yet delivered. */
  PENDING,
  /** Successfully delivered through the transport. */
  SENT,
  /** A delivery attempt failed (recorded, not swallowed). */
  FAILED
}
