package id.co.nativeapp.entitlement.entitlement.messaging;

/**
 * Thrown when a consumed record carries no valid durable {@code id} header. The Debezium outbox
 * event router always stamps the outbox row's {@code id} (a UUID) as this header, so its absence is
 * a producer-side contract violation. The consumer FAILS CLOSED: the container's error handler
 * treats this as non-retryable and routes the record to {@code CompanyCreated.DLT}, rather than
 * synthesising a non-durable id (which would defeat dedupe after a rebalance / compacted replay and
 * risk double-granting a company's defaults — HR-3).
 */
public class MissingEventIdException extends RuntimeException {

  public MissingEventIdException(String message) {
    super(message);
  }

  public MissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
