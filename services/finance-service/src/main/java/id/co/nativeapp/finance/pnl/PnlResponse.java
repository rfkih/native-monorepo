package id.co.nativeapp.finance.pnl;

/**
 * Consolidated P&amp;L query response for one period (the bound tenant).
 *
 * <p>Money is carried as the rule-8 pair — integer minor units + ISO-4217 {@code currency} — never
 * a float, for each of revenue, expense, and the derived net. The frontend formats each
 * locale-aware via {@code Intl.NumberFormat} reading the company base currency (no server-side
 * localized string; HR-9). A period with no postings yet returns zero revenue / expense / net in
 * the requested/base currency.
 *
 * @param period the accounting period {@code YYYY-MM}
 * @param revenueMinor accumulated revenue in the currency's minor units
 * @param expenseMinor accumulated expense in the currency's minor units
 * @param netMinor revenue − expense in the currency's minor units (derived, never stored)
 * @param currency the ISO-4217 currency code
 */
public record PnlResponse(
    String period, long revenueMinor, long expenseMinor, long netMinor, String currency) {

  /** Builds the response from a {@link ConsolidatedPnl} accumulator row. */
  static PnlResponse from(ConsolidatedPnl pnl) {
    return new PnlResponse(
        pnl.getPeriod(),
        pnl.revenue().amountMinor(),
        pnl.expense().amountMinor(),
        pnl.net().amountMinor(),
        pnl.revenue().currency().getCurrencyCode());
  }

  /** A zero P&amp;L in {@code currency} for a period with no postings yet. */
  static PnlResponse zero(String period, String currency) {
    return new PnlResponse(period, 0L, 0L, 0L, currency);
  }
}
