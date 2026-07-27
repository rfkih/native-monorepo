package id.co.nativeapp.finance.orgref.messaging;

/**
 * Thrown by {@link OrgUnitRefListener} when the raw Avro bytes cannot be decoded into an {@link
 * OrgUnitRefEvent} — a NON-RETRYABLE failure (the payload can never decode on retry).
 *
 * <p>The {@link id.co.nativeapp.finance.config.KafkaConfig} error handler treats this as
 * non-retryable and routes the record straight to {@code <topic>.DLT} without burning the bounded
 * retry budget. This prevents a poison org event from blocking the partition indefinitely.
 */
public class OrgUnitRefDecodeException extends RuntimeException {

  public OrgUnitRefDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
