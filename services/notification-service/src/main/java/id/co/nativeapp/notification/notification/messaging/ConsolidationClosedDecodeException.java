package id.co.nativeapp.notification.notification.messaging;

import id.co.nativeapp.notification.config.KafkaConfig;

/**
 * Thrown when a consumed {@code ConsolidationClosed} record's value cannot be decoded as a valid
 * Avro payload — garbage bytes, a truncated message, or a payload written with an incompatible
 * schema.
 *
 * <p>This is a deterministic, non-transient failure: the same bytes will never decode, so retrying
 * in place is futile and would block the partition. The container's error handler ({@link
 * KafkaConfig}) treats it as <em>non-retryable</em> and routes the record straight to {@code
 * ConsolidationClosed.DLT} for inspection rather than looping forever. No notification is created
 * for an undecodable trigger.
 */
public class ConsolidationClosedDecodeException extends RuntimeException {

  public ConsolidationClosedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
