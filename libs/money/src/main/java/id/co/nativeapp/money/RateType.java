package id.co.nativeapp.money;

/**
 * The kind of FX rate, shaped by IAS-21 currency-translation rules.
 *
 * <ul>
 *   <li>{@link #CLOSING} — the spot rate at the period-end (balance-sheet) date; used to translate
 *       closing <em>balances</em> (position items) into a presentation currency.
 *   <li>{@link #AVERAGE} — the period-average rate; used to translate <em>flows</em> (revenue,
 *       expense, and hence the P&amp;L net) over the period.
 *   <li>{@link #SPOT} — a point-in-time rate for an ad-hoc / transaction-date view.
 * </ul>
 *
 * <p>This enum sits beside {@link Money} in {@code libs/money} (not in a service) because it is
 * part of the shared FX value vocabulary an {@link FxRate} carries; it has no persistence or Spring
 * coupling.
 */
public enum RateType {
  /** Closing (period-end) rate — translates balances. */
  CLOSING,
  /** Period-average rate — translates flows (P&amp;L). */
  AVERAGE,
  /** Point-in-time spot rate — ad-hoc / transaction-date views. */
  SPOT
}
