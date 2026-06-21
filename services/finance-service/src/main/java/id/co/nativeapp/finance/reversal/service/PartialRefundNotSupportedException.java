package id.co.nativeapp.finance.reversal.service;

/**
 * Thrown when a {@code SaleRefunded} event carries a refund amount that is strictly less than the
 * original grand total (i.e. a partial refund), and the original SALE journal entry exists for
 * per-leg lookup.
 *
 * <p><strong>Why partial refunds are rejected.</strong> A full refund can be reversed per-leg
 * exactly (same amounts, debit ↔ credit swap — guaranteed balanced). A partial refund requires
 * prorating each original GL leg by {@code refundAmount / originalGrandTotal}, and integer rounding
 * cannot be guaranteed to produce a balanced contra entry in the general case (remainder
 * distribution introduces a rounding residual). Shipping an unbalanced or incoherent posting
 * violates the fundamental GL invariant; it is preferable to reject the event with a clear,
 * documented error and route it to the DLT for operator intervention.
 *
 * <p><strong>Resolution.</strong> A future event evolution ({@code SaleRefunded v2}) should carry
 * the per-leg prorated amounts pre-computed by the producer (who has the full breakdown), making
 * per-leg partial reversal exact and safe. Until then, operators must void the original sale and
 * re-record the adjusted amount as a new sale.
 *
 * <p>This is a deterministic failure: the same partial-refund event will always be rejected. The
 * container's error handler therefore routes it non-retryably to {@code SaleRefunded.DLT} where it
 * is preserved for inspection.
 */
public class PartialRefundNotSupportedException extends RuntimeException {

  public PartialRefundNotSupportedException(String message) {
    super(message);
  }
}
