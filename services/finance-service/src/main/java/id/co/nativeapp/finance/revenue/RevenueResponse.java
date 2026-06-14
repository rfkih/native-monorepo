package id.co.nativeapp.finance.revenue;

import id.co.nativeapp.money.Money;

/**
 * Consolidated-revenue query response for one period (the bound tenant).
 *
 * <p>Money is carried as the rule-8 pair — integer {@code totalMinor} (minor units) + ISO-4217
 * {@code currency} — never a float. The frontend formats it locale-aware via {@code
 * Intl.NumberFormat} reading the company base currency (no server-side localized string; HR-9). A
 * period with no postings yet returns a zero total in the requested/base currency.
 */
public record RevenueResponse(String period, long totalMinor, String currency) {

  /** Builds the response from a {@link Money} total. */
  static RevenueResponse of(String period, Money total) {
    return new RevenueResponse(period, total.amountMinor(), total.currency().getCurrencyCode());
  }
}
