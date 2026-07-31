package id.co.nativeapp.restaurant.entitlement.messaging;

/**
 * Thrown when an {@code EntitlementGranted}/{@code EntitlementRevoked} Kafka record has no valid
 * {@code id} header (a producer-side contract violation). The {@link
 * id.co.nativeapp.restaurant.config.KafkaConfig} error handler classifies this as
 * <strong>non-retryable</strong>: the record is routed immediately to the topic's {@code .DLT} —
 * fail closed. Mirrors {@code
 * outletref.messaging.UserOutletAssignmentMissingEventIdException} exactly.
 */
public class EntitlementMissingEventIdException extends RuntimeException {

  public EntitlementMissingEventIdException(String message) {
    super(message);
  }

  public EntitlementMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
