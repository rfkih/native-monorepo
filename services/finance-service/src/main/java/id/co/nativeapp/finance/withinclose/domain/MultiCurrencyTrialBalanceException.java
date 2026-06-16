package id.co.nativeapp.finance.withinclose.domain;

import java.io.Serial;

/**
 * Thrown when a within-company close finds the period's ledger postings in MORE THAN ONE currency
 * (P3d SEAM 4a — #42).
 *
 * <p>A company's postings for a period are all in its single base (functional) currency, set at
 * company creation and immutable once transactions exist (CLAUDE.md). A period holding two
 * currencies would need FX to roll up — out of scope for the within-company close (the same
 * boundary {@code PnlReader} draws). Rather than a bare {@link IllegalStateException} (which would
 * surface as a generic {@code 500}), this is a TYPED fault mapped to an RFC-7807 {@code 4xx} (the
 * advice maps it to {@code 422 Unprocessable Entity}) — the request is well-formed but the
 * underlying ledger state cannot be closed here — consistent with the other within-close faults
 * ({@link BaseCurrencyMismatchException}, {@link UndeterminableBaseCurrencyException}).
 */
public final class MultiCurrencyTrialBalanceException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param period the accounting period being closed
   * @param firstCurrency one ISO-4217 currency seen in the period's postings
   * @param otherCurrency a second, different ISO-4217 currency seen in the same period
   */
  public MultiCurrencyTrialBalanceException(
      String period, String firstCurrency, String otherCurrency) {
    super(
        "Within-company close for period "
            + period
            + " holds both "
            + firstCurrency
            + " and "
            + otherCurrency
            + " ledger lines; multi-currency within one company needs FX, out of scope for the"
            + " close (a company has one immutable base currency)");
  }
}
