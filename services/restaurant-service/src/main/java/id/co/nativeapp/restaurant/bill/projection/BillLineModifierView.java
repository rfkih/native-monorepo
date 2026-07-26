package id.co.nativeapp.restaurant.bill.projection;

import java.util.UUID;

/**
 * Read projection over the {@code bill_line_modifier} row — only the columns a response needs.
 *
 * <p>Backs the native read queries on {@link
 * id.co.nativeapp.restaurant.bill.repository.BillLineModifierRepository}.
 */
public interface BillLineModifierView {

  UUID getBillLineId();

  UUID getOptionId();

  String getNameSnapshot();

  long getPriceDeltaMinor();
}
