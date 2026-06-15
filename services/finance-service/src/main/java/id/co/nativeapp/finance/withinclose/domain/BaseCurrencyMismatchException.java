package id.co.nativeapp.finance.withinclose.domain;

import java.io.Serial;

/**
 * Thrown when a within-company close request DECLARES a base currency (the optional body
 * cross-check) that does NOT match the base currency DERIVED from the company's ledger for the
 * period (P3d SEAM 4a).
 *
 * <p>Per CLAUDE.md the company's base (functional) currency is set at company creation and is
 * IMMUTABLE once transactions exist. The close therefore DERIVES the authoritative base currency
 * from the period's postings — it is never declared by the caller. A body-supplied {@code
 * baseCurrency} is only an OPTIONAL cross-check; a mismatch is a FAIL-LOUD: the dashboard's idea of
 * the base currency disagrees with the books, which must surface as a clear {@code 4xx} (the
 * RFC-7807 advice maps it to {@code 422 Unprocessable Entity} — the request is well-formed but
 * conflicts with the ledger reality), never silently overridden.
 */
public final class BaseCurrencyMismatchException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param period the accounting period being closed
   * @param declaredCurrency the body-supplied (cross-check) ISO-4217 currency
   * @param ledgerCurrency the base currency derived from the ledger (the source of truth)
   */
  public BaseCurrencyMismatchException(
      String period, String declaredCurrency, String ledgerCurrency) {
    super(
        "Within-company close for period "
            + period
            + " declared base currency "
            + declaredCurrency
            + " but the company ledger's base currency is "
            + ledgerCurrency
            + "; the base currency is immutable and is derived from the books, not the request");
  }
}
