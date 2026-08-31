package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * Thrown when a caller without the {@code owner}/{@code manager} role attempts a destructive
 * open-bill mutation (cancelling a bill that has lines, or removing a line) — the open-bill
 * lockdown: once a bill holds items its flow must end in payment unless a manager intervenes.
 * Maps to {@code 403 Forbidden} via {@link
 * id.co.nativeapp.restaurant.bill.controller.BillExceptionHandler}.
 *
 * <p>Same role source and empty-roles-pass semantics as {@link
 * id.co.nativeapp.restaurant.promotion.service.ManualDiscountGuard}: the gateway-stamped {@code
 * X-Roles} header; a headerless caller (gateway-less dev recipe / direct service-layer test) is
 * trusted.
 */
public class BillMutationForbiddenException extends RuntimeException {

  private final UUID billId;
  private final String action;

  public BillMutationForbiddenException(UUID billId, String action) {
    super(
        "Action '"
            + action
            + "' on bill "
            + billId
            + " requires the owner or manager role — an open bill with items must end in payment.");
    this.billId = billId;
    this.action = action;
  }

  public UUID getBillId() {
    return billId;
  }

  public String getAction() {
    return action;
  }
}
