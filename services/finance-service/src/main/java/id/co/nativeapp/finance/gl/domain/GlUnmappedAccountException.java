package id.co.nativeapp.finance.gl.domain;

import java.io.Serial;

/**
 * Thrown when a GL trial-balance line references an {@code account_code} that has NO {@code
 * chart_of_account} row, so its {@code account_type} cannot be resolved.
 *
 * <p>The trial-balance aggregation {@code LEFT JOIN}s {@code chart_of_account}; an unmappable
 * account surfaces as a line with a {@code NULL} {@code account_type}. Were the join an {@code
 * INNER JOIN}, such a line would be SILENTLY DROPPED — its money would vanish while the statement
 * still appeared to balance (money silently lost, violating HR-3). The chart/mapping seed should
 * prevent an unmapped account, so this is an INTERNAL data-integrity fault: it FAILS LOUD here
 * rather than understating the books. Like {@code
 * withinclose.domain.UnmappedLedgerAccountException} it is NOT mapped to a {@code 4xx} — it falls
 * through to the shared non-leaking catch-all {@code 500}, since it is a server-side bug (the books
 * reference an account the chart does not define), not a client-fixable condition.
 */
public final class GlUnmappedAccountException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param context a human description of the scope, e.g. {@code "period 2026-06"} or {@code
   *     "periods up to 2026-06"}
   * @param accountCode the {@code account_code} with no {@code chart_of_account} row
   */
  public GlUnmappedAccountException(String context, String accountCode) {
    super(
        "GL trial balance for "
            + context
            + " references account "
            + accountCode
            + " with no account_type in chart_of_account (unmapped); seed the account before"
            + " deriving a statement rather than silently misclassifying it");
  }
}
