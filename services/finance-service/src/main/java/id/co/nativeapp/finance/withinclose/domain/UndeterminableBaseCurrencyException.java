package id.co.nativeapp.finance.withinclose.domain;

import java.io.Serial;

/**
 * Thrown when a within-company close cannot DERIVE a base currency: the period has no postings (so
 * the ledger reveals no currency) AND the request supplied no cross-check {@code baseCurrency} to
 * fall back to for the audit record (P3d SEAM 4a).
 *
 * <p>An EMPTY period emits only {@code ConsolidationClosed} (which carries no currency) — no {@code
 * TrialBalancePublished} is produced, so no authoritative currency leaks onto the wire. But the
 * {@code within_company_close} audit row still records a base currency; with neither a ledger
 * currency to derive nor a declared one to record, the close cannot proceed. This is a FAIL-LOUD
 * {@code 4xx} (mapped to {@code 422}) rather than fabricating a currency the company never had.
 */
public final class UndeterminableBaseCurrencyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param period the empty accounting period the close could not resolve a currency for
   */
  public UndeterminableBaseCurrencyException(String period) {
    super(
        "Within-company close for period "
            + period
            + " has no ledger postings and no base currency was supplied to record; cannot derive"
            + " the company base currency (it is set at company creation and read from the books)");
  }
}
