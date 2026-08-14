package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when a {@code bill_line} write's guarded {@code WHERE} clause (V38) affects FEWER rows
 * than the target line count read moments earlier in the SAME transaction — a concurrent racer
 * claimed one or more of those lines between the read and the write. Two callers share this one
 * exception type:
 *
 * <ul>
 *   <li>{@code BillWriter.initiatePendingPayment}'s reservation UPDATE ({@code
 *       BillLineRepository#reserveUnpaidLines}) — a concurrent cash {@code payBill} paid some of
 *       the target lines first.
 *   <li>{@code BillWriter.recordCheck}'s mark-paid UPDATE, both the cash-path guard ({@code
 *       BillLineRepository#markLinesPaidForCash}) — a concurrent gateway payment reserved some of
 *       the target lines first (the C1 fix: without this the cash write would silently clobber a
 *       live reservation, double-charging the customer) — and the capture-path guard ({@code
 *       BillLineRepository#markLinesPaidForCapture}) — a concurrent abandon/re-reservation moved
 *       the line out from under this specific payment's capture.
 * </ul>
 *
 * <p>Maps to {@code 409 Conflict} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}. Extends {@link
 * IllegalStateException} (not a bare {@link RuntimeException}) so a capture-path occurrence is ALSO
 * automatically caught by {@code PaymentChargeSucceededWriter}'s existing park-don't-drop {@code
 * catch (IllegalArgumentException | IllegalStateException | InsufficientStockException)} clause —
 * real money may already be moving at the gateway, so this parks for a human instead of poison-
 * looping the Kafka redelivery. Either way the whole transaction (including anything already
 * written in it — a freshly-minted PENDING payment, or an already-recorded sale + outbox row) rolls
 * back, so a client-side retry (or the async redelivery) is always safe against fresh bill state.
 */
public class BillLineReservationConflictException extends IllegalStateException {

  private final UUID billId;
  private final int expectedUnpaidLines;
  private final int reservedLines;

  public BillLineReservationConflictException(
      UUID billId, int expectedUnpaidLines, int reservedLines) {
    super(
        "Bill "
            + billId
            + " line-claim conflict: expected to claim "
            + expectedUnpaidLines
            + " bill line(s) but only claimed "
            + reservedLines
            + " — a concurrent payment already claimed some of them; retry");
    this.billId = billId;
    this.expectedUnpaidLines = expectedUnpaidLines;
    this.reservedLines = reservedLines;
  }

  public UUID getBillId() {
    return billId;
  }

  public int getExpectedUnpaidLines() {
    return expectedUnpaidLines;
  }

  public int getReservedLines() {
    return reservedLines;
  }
}
