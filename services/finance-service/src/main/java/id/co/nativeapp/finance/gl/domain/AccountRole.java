package id.co.nativeapp.finance.gl.domain;

/**
 * A semantic role used in a {@link PostingTemplate} line, resolved to a concrete {@code
 * chart_of_account.account_code} via the {@code role_account_map} reference data. Roles are
 * SME-pluggable: an accountant seeds a new {@code role_account_map} row pointing an {@code
 * AccountRole} to the correct account for their jurisdiction without touching Java code.
 *
 * <p>The illustrative defaults seeded in V13 use a minimal set of roles sufficient for an
 * end-to-end balanced journal for each {@link EventKind}. The full Indonesian COA roles ({@code
 * TAX_PAYABLE}, {@code AR}, {@code AP}, etc.) are reserved for the SME phase.
 */
public enum AccountRole {
  CASH_CLEARING,
  REVENUE,
  EXPENSE,
  LABOR_EXPENSE,
  LABOR_CLEARING,
  SUSPENSE
}
