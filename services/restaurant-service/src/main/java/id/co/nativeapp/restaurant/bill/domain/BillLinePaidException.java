package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when a remove is attempted on a bill line that is already PAID — its sale is recorded;
 * removing it would silently detach money from the bill (the frontend never offers this, the
 * server refuses it regardless). Maps to {@code 409 Conflict} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 */
public class BillLinePaidException extends RuntimeException {

  private final UUID billId;
  private final UUID lineId;

  public BillLinePaidException(UUID billId, UUID lineId) {
    super("Line " + lineId + " on bill " + billId + " is already paid and cannot be removed.");
    this.billId = billId;
    this.lineId = lineId;
  }

  public UUID getBillId() {
    return billId;
  }

  public UUID getLineId() {
    return lineId;
  }
}
