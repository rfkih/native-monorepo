package id.co.nativeapp.notification.notification.domain;

/**
 * The delivery channel of a notification. Matches the {@code notification.channel} check constraint
 * (EMAIL | PUSH) in the Flyway baseline.
 */
public enum Channel {
  /** Email delivery (the channel used for a consolidation-closed notice in this slice). */
  EMAIL,
  /** Push delivery (device/web push). */
  PUSH
}
