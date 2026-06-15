package id.co.nativeapp.finance.group.messaging;

/**
 * Thrown when a {@code GroupMembershipChanged} record's value is not a decodable Avro payload. A
 * NON-RETRYABLE poison routed straight to {@code GroupMembershipChanged.DLT} by the container's
 * error handler ({@link id.co.nativeapp.finance.config.KafkaConfig}) — preserved for inspection,
 * never silently dropped, and no read-model row is written.
 */
public class GroupMembershipChangedDecodeException extends RuntimeException {

  public GroupMembershipChangedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
