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
}
