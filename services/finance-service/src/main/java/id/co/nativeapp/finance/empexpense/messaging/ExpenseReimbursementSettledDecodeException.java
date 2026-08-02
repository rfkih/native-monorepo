package id.co.nativeapp.finance.empexpense.messaging;

/**
 * Thrown when a consumed {@code ExpenseReimbursementSettled} record's value cannot be decoded as a
 * valid Avro payload — garbage bytes, a truncated message, or a payload written with an
 * incompatible schema.
 *
 * <p>This is a deterministic, non-transient failure: the same bytes will never decode, so retrying
 * in place is futile and would block the partition. The container's error handler (see {@link
 * id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em> and routes the
 * record straight to {@code ExpenseReimbursementSettled.DLT}. Money is thus never silently dropped
 * — the undecodable record is preserved on the DLT for inspection — and the payable is never
 * settled for it.
 */
public class ExpenseReimbursementSettledDecodeException extends RuntimeException {

  public ExpenseReimbursementSettledDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
