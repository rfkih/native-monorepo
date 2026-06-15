package id.co.nativeapp.finance.withinclose.domain;

import java.io.Serial;

/**
 * Thrown when a within-company close encounters a ledger posting whose {@code gl_account_code} has
 * NO {@code chart_of_account} row, so its {@code account_type} cannot be resolved (P3d SEAM 4a —
 * defence-in-depth).
 *
 * <p>The trial-balance aggregation {@code LEFT JOIN}s {@code chart_of_account}; an unmappable
 * posting surfaces as a line with a {@code NULL} {@code account_type}. Were the join an {@code
 * INNER JOIN}, such a posting would be SILENTLY DROPPED from the trial balance — its money would
 * vanish while the synthesized equity closing line still balanced to the now-understated net (money
 * silently lost, violating HR-3). The mapping-rule FK prevents an unmapped account today, but the
 * close FAILS LOUD here rather than ever understating the books: a clear error (mapped to {@code
 * 5xx}, an internal data-integrity fault — the books reference an account the chart does not
 * define) over a silent drop.
 */
public final class UnmappedLedgerAccountException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param period the accounting period being closed
   * @param glAccountCode the posting's {@code gl_account_code} with no {@code chart_of_account} row
   */
  public UnmappedLedgerAccountException(String period, String glAccountCode) {
    super(
        "Within-company close for period "
            + period
            + " has a ledger posting to gl_account_code "
            + glAccountCode
            + " with no chart_of_account row (unresolvable account_type); failing the close rather"
            + " than silently dropping the posting from the trial balance");
  }
}
