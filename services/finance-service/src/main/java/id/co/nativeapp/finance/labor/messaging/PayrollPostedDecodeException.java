package id.co.nativeapp.finance.labor.messaging;

/**
 * Thrown when a consumed {@code PayrollPosted} record's value cannot be decoded as a valid Avro
 * payload — garbage bytes, a truncated message, or a payload written with an incompatible schema
 * (#23).
 *
 * <p>A deterministic, non-transient failure. The container's error handler ({@link
 * id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em> and routes the
 * record straight to {@code PayrollPosted.DLT} — never an infinite in-place retry that blocks the
 * partition. {@code PayrollPosted} produces no ledger posting, so no side effect is written for a
 * poison record; the undecodable record is preserved on the DLT for inspection.
 */
public class PayrollPostedDecodeException extends RuntimeException {

  public PayrollPostedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
