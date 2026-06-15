package id.co.nativeapp.employee.org.messaging;

/**
 * Raised when an {@code OrgUnitCreated}/{@code OrgUnitChanged} record's value is not a valid Avro
 * payload (garbage / truncated bytes / a wrong schema). It can never decode, so retrying in place
 * is futile and would block the partition; the container's error handler ({@link
 * id.co.nativeapp.employee.config.KafkaConfig}) classifies it non-retryable and routes the record
 * straight to {@code <topic>.DLT}.
 */
public class OrgUnitDecodeException extends RuntimeException {

  public OrgUnitDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
