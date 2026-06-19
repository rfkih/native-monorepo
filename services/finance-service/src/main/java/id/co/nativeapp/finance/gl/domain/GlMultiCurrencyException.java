package id.co.nativeapp.finance.gl.domain;

import java.io.Serial;

/**
 * Thrown when a GL trial balance (period or cumulative) spans MORE THAN ONE currency, so a
 * financial statement cannot be derived from it without FX.
 *
 * <p>A company's postings are all in its single base (functional) currency, set at company creation
 * and immutable once transactions exist (CLAUDE.md). A trial balance holding two currencies would
 * need FX to roll up — out of scope for the financial-statements read layer (the same boundary
 * {@code PnlReader} and the within-company close draw). Rather than a bare {@link
 * IllegalStateException} (which would surface as a generic {@code 500}), this is a TYPED fault
 * mapped to an RFC-7807 {@code 422 Unprocessable Entity} by {@code ConstraintViolationAdvice} — the
 * request is well-formed but the underlying ledger state cannot be presented as a single-currency
 * statement. Mirrors {@code withinclose.domain.MultiCurrencyTrialBalanceException}.
 */
public final class GlMultiCurrencyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param context a human description of the scope, e.g. {@code "period 2026-06"} or {@code
   *     "periods up to 2026-06"}
   * @param firstCurrency one ISO-4217 currency seen in the trial balance
   * @param otherCurrency a second, different ISO-4217 currency seen in the same trial balance
   */
  public GlMultiCurrencyException(String context, String firstCurrency, String otherCurrency) {
    super(
        "GL trial balance for "
            + context
            + " spans more than one currency ("
            + firstCurrency
            + " and "
            + otherCurrency
            + "); multi-currency within one company needs FX (a company has one immutable base"
            + " currency), out of scope for financial statements");
  }
}
