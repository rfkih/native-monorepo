package id.co.nativeapp.notification.notification.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The result a {@link NotificationSender} returns from a delivery attempt: the outcome status
 * (DELIVERED / FAILED), the transport's reference for the attempt (a synthetic id for the stub, or
 * null when none), and the instant the attempt completed.
 *
 * <p>A FAILED outcome is a normal return value, not an exception — a delivery failure is recorded
 * (a FAILED notification + receipt + event), never swallowed and never thrown out of the consumer
 * (which would DLT the trigger and lose the recorded failure).
 *
 * @param status the delivery outcome
 * @param providerRef the transport reference, or {@code null}
 * @param at when the attempt completed (UTC)
 */
public record DeliveryOutcome(DeliveryStatus status, String providerRef, Instant at) {

  public DeliveryOutcome {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(at, "at");
  }

  /** A successful delivery with a provider reference. */
  public static DeliveryOutcome delivered(String providerRef, Instant at) {
    return new DeliveryOutcome(DeliveryStatus.DELIVERED, providerRef, at);
  }

  /** A failed delivery; {@code providerRef} may be null when the transport returned none. */
  public static DeliveryOutcome failed(String providerRef, Instant at) {
    return new DeliveryOutcome(DeliveryStatus.FAILED, providerRef, at);
  }

  /** Whether the delivery succeeded. */
  public boolean isDelivered() {
    return status == DeliveryStatus.DELIVERED;
  }
}
