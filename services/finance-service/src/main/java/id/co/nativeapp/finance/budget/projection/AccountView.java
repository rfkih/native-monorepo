package id.co.nativeapp.finance.budget.projection;

/**
 * Read projection over a {@code chart_of_account} row (Phase 5) — the account catalog the budget
 * create form picks from. {@code chart_of_account} is global reference data (not tenant-scoped), so
 * this returns every account regardless of tenant.
 */
public interface AccountView {

  String getAccountCode();

  String getName();

  String getAccountType();
}
