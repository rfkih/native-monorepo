package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when removing a {@link BillLine} that is currently RESERVED for an in-flight gateway
 * payment (V38, {@code bill_line.pending_payment_id IS NOT NULL}) — hardening fix (code review).
 * Removing it out from under the payment would strand real money at the PSP: the eventual capture
 * would resolve a reserved-lines set missing this line, recompute a check breakdown that no longer
 * reconciles to the amount authorized at mint, and PARK instead of settling (see {@code
 * BillPaymentCaptureWriter}'s recompute-and-assert guard) — a human would then have to untangle
 * already-moved gateway money. The caller must abandon the payment first (releasing the
 * reservation), or wait for it to capture.
 *
 * <p>Maps to {@code 409 Conflict} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 */
public class BillLineReservedException extends RuntimeException {

  private final UUID billId;
  private final UUID lineId;
  private final UUID pendingPaymentId;

  public BillLineReservedException(UUID billId, UUID lineId, UUID pendingPaymentId) {
    super(
        "Bill line "
            + lineId
            + " on bill "
            + billId
            + " is reserved by an in-flight payment ("
            + pendingPaymentId
            + "); abandon it first");
    this.billId = billId;
    this.lineId = lineId;
    this.pendingPaymentId = pendingPaymentId;
  }

  public UUID getBillId() {
    return billId;
  }

  public UUID getLineId() {
    return lineId;
  }

  public UUID getPendingPaymentId() {
    return pendingPaymentId;
  }
}
