package id.co.nativeapp.finance.orgref.messaging;

/**
 * Thrown by {@link OrgUnitRefListener} when the Kafka record has no valid {@code id} header — a
 * NON-RETRYABLE producer-side contract violation.
 *
 * <p>The Debezium outbox event router ALWAYS stamps the outbox row's {@code id} (a UUID) as the
 * {@code id} header; its absence or a non-UUID value means a misconfigured producer. We FAIL CLOSED
 * — the record is routed to {@code <topic>.DLT} immediately (no retries), so a missing id cannot
 * defeat the {@code ProcessedEventStore} dedupe and risk double-applying the org projection.
 */
public class OrgUnitRefMissingEventIdException extends RuntimeException {

  public OrgUnitRefMissingEventIdException(String message) {
    super(message);
  }

  public OrgUnitRefMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
