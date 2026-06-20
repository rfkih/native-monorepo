package id.co.nativeapp.finance.reversal.messaging;

/**
 * Thrown when a consumed {@code SaleRefunded} record's value cannot be decoded as a valid Avro
 * payload — garbage bytes, a truncated message, or a payload written with an incompatible schema.
 *
 * <p>This is a deterministic, non-transient failure: the same bytes will never decode, so retrying
 * in place is futile and would block the partition (stalling finance reversal posting). The
 * container's error handler (see {@link id.co.nativeapp.finance.config.KafkaConfig}) treats it as
 * <em>non-retryable</em> and routes the record straight to {@code SaleRefunded.DLT}. Money is thus
 * never silently dropped — the undecodable record is preserved on the DLT for inspection — and no
 * reversal ledger posting is written for it.
 */
public class SaleRefundedDecodeException extends RuntimeException {

  public SaleRefundedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
