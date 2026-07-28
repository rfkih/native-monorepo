package id.co.nativeapp.restaurant.outletref.messaging;

/**
 * Thrown when a {@code UserOutletAssignmentChanged} Kafka record has no valid {@code id} header (a
 * producer-side contract violation). The {@link id.co.nativeapp.restaurant.config.KafkaConfig}
 * error handler classifies this as <strong>non-retryable</strong>: the record is routed immediately
 * to {@code UserOutletAssignmentChanged.DLT} — fail closed.
 */
public class UserOutletAssignmentMissingEventIdException extends RuntimeException {

  public UserOutletAssignmentMissingEventIdException(String message) {
    super(message);
  }

  public UserOutletAssignmentMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
