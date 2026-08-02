package id.co.nativeapp.finance.labor.messaging;

/**
 * Thrown when a consumed {@code PayrollLiabilitiesPosted} record's value cannot be decoded as a
 * valid Avro payload — garbage bytes, a truncated message, or a payload written with an
 * incompatible schema (ADR 0032, Track P phase P4).
 *
 * <p>A deterministic, non-transient failure: the same bytes will never decode, so retrying in place
 * is futile and would block the partition. The container's error handler ({@link
 * id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em> and routes the
 * record straight to {@code PayrollLiabilitiesPosted.DLT} — money is never silently dropped (the
 * undecodable record is preserved on the DLT) and no journal entry is written.
 */
public class PayrollLiabilitiesPostedDecodeException extends RuntimeException {

  public PayrollLiabilitiesPostedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
