package id.co.nativeapp.finance.group;

/**
 * Thrown when a {@code GroupDefined} record's value is not a decodable Avro payload (garbage /
 * truncated bytes / wrong schema). It is a NON-RETRYABLE poison: retrying in place is futile and
 * would block the partition, so the container's error handler ({@link
 * id.co.nativeapp.finance.config.KafkaConfig}) routes it straight to {@code GroupDefined.DLT} — the
 * record is preserved for inspection, never silently dropped, and no read-model row is written.
 */
public class GroupDefinedDecodeException extends RuntimeException {

  public GroupDefinedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
