package id.co.nativeapp.restaurant.outletref.messaging;

/**
 * Thrown when a {@code UserOutletAssignmentChanged} payload cannot be decoded (garbage bytes,
 * truncated Avro, or a schema mismatch). The {@link id.co.nativeapp.restaurant.config.KafkaConfig}
 * error handler classifies this as <strong>non-retryable</strong>: a record that cannot be decoded
 * will never succeed on retry and is routed immediately to {@code UserOutletAssignmentChanged.DLT}
 * — fail closed, never an infinite in-place retry that blocks the partition.
 */
public class UserOutletAssignmentDecodeException extends RuntimeException {

  public UserOutletAssignmentDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
