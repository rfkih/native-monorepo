package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when a requested {@link Bill} is not found (or is not visible to the current tenant due to
 * RLS). Maps to {@code 404 Not Found} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 */
public class BillNotFoundException extends RuntimeException {

  private final UUID billId;

  public BillNotFoundException(UUID billId) {
    super("Bill not found: " + billId);
    this.billId = billId;
  }

  public UUID getBillId() {
    return billId;
  }
}
