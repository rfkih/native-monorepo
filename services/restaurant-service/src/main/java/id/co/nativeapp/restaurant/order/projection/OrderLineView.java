package id.co.nativeapp.restaurant.order.projection;

import java.util.UUID;

/**
 * Read projection over the {@code order_line} row — only the columns a response needs, never the
 * {@link id.co.nativeapp.tenant.Auditable Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code OrderLineRepository}.
 */
public interface OrderLineView {

  UUID getId();

  UUID getMenuItemId();

  String getNameSnapshot();

  long getUnitPriceMinor();

  int getQty();

  long getLineTotalMinor();
}
