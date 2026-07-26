package id.co.nativeapp.restaurant.bill.projection;

import java.util.UUID;

/**
 * Read projection for the list endpoint: a bill summary with its running total (sum of line
 * totals) and line count. Does NOT include line details — use {@link BillView} + {@link
 * BillLineView} for the detail view.
 *
 * <p>Backs the native list query on {@link
 * id.co.nativeapp.restaurant.bill.repository.BillRepository}.
 */
public interface BillSummaryView {

  UUID getId();

  UUID getBusinessId();

  UUID getTableId();

  String getGuestLabel();

  String getStatus();

  String getCurrency();

  Long getDiscountMinor();

  /** Sum of {@code bill_line.line_total_minor} for all lines on this bill. */
  long getRunningTotalMinor();

  int getLineCount();
}
