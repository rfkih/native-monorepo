package id.co.nativeapp.employee.org.messaging;

/**
 * Raised when a consumed org-event record has no valid durable {@code id} header — a producer-side
 * contract violation (the Debezium outbox event router always stamps the outbox row's {@code id}
 * UUID as this header). The consumer FAILS CLOSED rather than synthesising a positional id (which
 * would defeat dedupe after a rebalance / compacted replay and risk double-applying an org-tree
 * update — exactly what HR-3 forbids). The container's error handler treats it as non-retryable and
 * routes the record to {@code <topic>.DLT}.
 */
public class MissingEventIdException extends RuntimeException {

  public MissingEventIdException(String message) {
    super(message);
  }

  public MissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
