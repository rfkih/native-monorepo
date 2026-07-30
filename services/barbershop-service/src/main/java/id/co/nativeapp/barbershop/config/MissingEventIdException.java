package id.co.nativeapp.barbershop.config;

/**
 * Thrown when a consumed record carries no valid durable {@code id} header. The Debezium outbox
 * event router always stamps the outbox row's {@code id} (a UUID) as this header, so its absence is
 * a producer-side contract violation. The consumer FAILS CLOSED: the container's error handler
 * ({@link KafkaConfig}) treats this as non-retryable and routes the record to {@code <topic>.DLT},
 * rather than synthesising a non-durable id (which would defeat dedupe after a rebalance /
 * compacted replay and risk double-applying a projection update — HR-3).
 */
public class MissingEventIdException extends RuntimeException {

  public MissingEventIdException(String message) {
    super(message);
  }

  public MissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
