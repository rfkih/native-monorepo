package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when a cancel is attempted on an OPEN bill that already carries PAID lines (a partially
 * paid split-check). Cancelling would strand the recorded sales — the remainder must be settled
 * (or the paid checks reversed via the return-sale flow) first. Applies to EVERY role. Maps to
 * {@code 409 Conflict} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 */
public class BillHasPaidLinesException extends RuntimeException {

  private final UUID billId;
  private final int paidLineCount;

  public BillHasPaidLinesException(UUID billId, int paidLineCount) {
    super(
        "Bill "
            + billId
            + " cannot be cancelled: "
            + paidLineCount
            + " line(s) are already paid — settle the remainder or reverse the paid checks first.");
    this.billId = billId;
    this.paidLineCount = paidLineCount;
  }

  public UUID getBillId() {
    return billId;
  }

  public int getPaidLineCount() {
    return paidLineCount;
  }
}
