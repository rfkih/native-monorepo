package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when an operation that requires an OPEN bill is attempted on a bill that is PAID or
 * CANCELLED. Maps to {@code 409 Conflict} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 */
public class BillNotOpenException extends RuntimeException {

  private final UUID billId;
  private final String currentStatus;

  public BillNotOpenException(UUID billId, String currentStatus) {
    super("Bill " + billId + " is not OPEN; current status: " + currentStatus);
    this.billId = billId;
    this.currentStatus = currentStatus;
  }

  public UUID getBillId() {
    return billId;
  }

  public String getCurrentStatus() {
    return currentStatus;
  }
}
