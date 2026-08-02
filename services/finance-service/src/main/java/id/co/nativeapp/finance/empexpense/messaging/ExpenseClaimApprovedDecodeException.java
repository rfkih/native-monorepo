package id.co.nativeapp.finance.empexpense.messaging;

/**
 * Thrown when a consumed {@code ExpenseClaimApproved} record's value cannot be decoded as a valid
 * Avro payload — garbage bytes, a truncated message, or a payload written with an incompatible
 * schema.
 *
 * <p>This is a deterministic, non-transient failure: the same bytes will never decode, so retrying
 * in place is futile and would block the partition. The container's error handler (see {@link
 * id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em> and routes the
 * record straight to {@code ExpenseClaimApproved.DLT}. Money is thus never silently dropped — the
 * undecodable record is preserved on the DLT for inspection — and no posting is written for it.
 *
 * <p>Note the distinction from an UNMAPPABLE {@code gl_hint}: a gl_hint with no matching {@code
 * mapping_rule} is NOT poison — the event decodes fine and IS posted, to the suspense account. Only
 * an undecodable payload (this exception) goes to the DLT.
 */
public class ExpenseClaimApprovedDecodeException extends RuntimeException {

  public ExpenseClaimApprovedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
