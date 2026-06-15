package id.co.nativeapp.finance.expense.messaging;

/**
 * Thrown when a consumed {@code ExpenseRecorded} record's value cannot be decoded as a valid Avro
 * payload — garbage bytes, a truncated message, or a payload written with an incompatible schema.
 *
 * <p>This is a deterministic, non-transient failure: the same bytes will never decode, so retrying
 * in place is futile and would block the partition (stalling finance consolidation). The
 * container's error handler (see {@link id.co.nativeapp.finance.config.KafkaConfig}) treats it as
 * <em>non-retryable</em> and routes the record straight to {@code ExpenseRecorded.DLT}. Money is
 * thus never silently dropped — the undecodable record is preserved on the DLT for inspection — and
 * no {@code ledger_posting} is written for it.
 *
 * <p>Note the distinction from an UNMAPPABLE {@code gl_hint}: a gl_hint with no matching {@code
 * mapping_rule} is NOT poison — the event decodes fine and IS posted, to the suspense account (the
 * money stays on the books). Only an undecodable payload (this exception) goes to the DLT.
 */
public class ExpenseRecordedDecodeException extends RuntimeException {

  public ExpenseRecordedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
