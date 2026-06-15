package id.co.nativeapp.finance.labor.messaging;

/**
 * Thrown when a consumed {@code LaborCostAllocated} record's value cannot be decoded as a valid
 * Avro payload — garbage bytes, a truncated message, or a payload written with an incompatible
 * schema (#23).
 *
 * <p>A deterministic, non-transient failure: the same bytes will never decode, so retrying in place
 * is futile and would block the partition (stalling finance consolidation). The container's error
 * handler ({@link id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em>
 * and routes the record straight to {@code LaborCostAllocated.DLT} — money is never silently
 * dropped (the undecodable record is preserved on the DLT) and no {@code ledger_posting} is
 * written.
 *
 * <p>Note the distinction from an UNMAPPABLE labor {@code gl_account}: a hint with no matching
 * {@code mapping_rule} is NOT poison — the event decodes fine and IS posted, to the suspense
 * account (money stays on the books). Only an undecodable payload (this exception) goes to the DLT.
 */
public class LaborCostAllocatedDecodeException extends RuntimeException {

  public LaborCostAllocatedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
