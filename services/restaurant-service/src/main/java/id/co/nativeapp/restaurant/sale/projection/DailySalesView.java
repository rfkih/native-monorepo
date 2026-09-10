package id.co.nativeapp.restaurant.sale.projection;

import java.time.LocalDate;

/**
 * Read projection for the per-day sales summary ({@code GET /api/v1/sales/daily}) — one row per
 * outlet-local calendar day that had a tendered sale or a refund: the day's net (total − refunds),
 * its transaction count, and the sale-time COGS fold (V44) with how many of the day's sales carried
 * one.
 *
 * <p>Backs {@code SaleRepository.findDailySummary}. Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention (CLAUDE.md "native-query aliases
 * snake_case; map via projection interfaces"). Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface DailySalesView {

  LocalDate getBusinessDate();

  long getTransactionCount();

  long getNetSalesMinor();

  /** {@code null} when no sale that day carried a COGS fold. */
  Long getCogsMinor();

  long getCostedTransactionCount();

  String getCurrency();

  boolean getUsesIllustrativeRules();
}
