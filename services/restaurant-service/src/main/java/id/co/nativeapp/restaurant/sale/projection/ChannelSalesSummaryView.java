package id.co.nativeapp.restaurant.sale.projection;

/**
 * Read projection for the per-channel ONLINE sales summary ({@code GET
 * /api/v1/sales/channel-summary}) — one row per {@code (channel_code, currency)} with the period's
 * gross sales total and transaction count.
 *
 * <p>Backs {@code SaleRepository.findChannelSummary}. Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention (CLAUDE.md "native-query aliases
 * snake_case; map via projection interfaces"). Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface ChannelSalesSummaryView {

  String getChannelCode();

  long getGrossSalesMinor();

  long getTransactionCount();

  String getCurrency();
}
