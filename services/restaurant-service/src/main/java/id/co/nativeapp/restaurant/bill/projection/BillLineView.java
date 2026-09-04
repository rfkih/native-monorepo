package id.co.nativeapp.restaurant.bill.projection;

import java.util.UUID;

/**
 * Read projection over the {@code bill_line} row — only the columns a response needs.
 *
 * <p>Backs the native read queries on {@link
 * id.co.nativeapp.restaurant.bill.repository.BillLineRepository}. Mirrors the structure of {@link
 * id.co.nativeapp.restaurant.order.projection.OrderLineView}.
 */
public interface BillLineView {

  UUID getId();

  UUID getMenuItemId();

  String getNameSnapshot();

  long getUnitPriceMinor();

  long getModifierDeltaMinor();

  int getQty();

  long getLineTotalMinor();

  boolean isPaid();

  UUID getPaidSaleId();

  /**
   * The in-flight gateway payment this line is currently RESERVED against (V38), or {@code null}
   * when unreserved. {@link id.co.nativeapp.restaurant.bill.service.BillWriter#payBill} excludes a
   * reserved-but-unpaid line from a cash/manual check — the reservation holds until the gateway
   * payment is either captured or abandoned.
   */
  UUID getPendingPaymentId();
}
