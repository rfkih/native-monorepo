package id.co.nativeapp.loyalty.config;

/**
 * Thrown when a consumed record's value cannot be decoded as a valid Avro payload (garbage /
 * truncated bytes, a wrong schema). It is a poison record: it can never decode, so retrying in
 * place is futile and would block the partition. The container's error handler ({@link
 * KafkaConfig}) treats it as non-retryable and routes the record straight to {@code <topic>.DLT}
 * for inspection rather than looping forever.
 */
public class EventDecodeException extends RuntimeException {

  public EventDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
