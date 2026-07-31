package id.co.nativeapp.restaurant.entitlement.messaging;

/**
 * Thrown when an {@code EntitlementGranted}/{@code EntitlementRevoked} payload cannot be decoded
 * (garbage bytes, truncated Avro, or a schema mismatch). The {@link
 * id.co.nativeapp.restaurant.config.KafkaConfig} error handler classifies this as
 * <strong>non-retryable</strong>: a record that cannot be decoded will never succeed on retry and is
 * routed immediately to the topic's {@code .DLT} — fail closed, never an infinite in-place retry
 * that blocks the partition. Mirrors {@code outletref.messaging.UserOutletAssignmentDecodeException}
 * exactly.
 */
public class EntitlementDecodeException extends RuntimeException {

  public EntitlementDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
