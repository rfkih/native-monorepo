package id.co.nativeapp.finance.budget.projection;

/**
 * Read projection over a {@code budget_line} joined to {@code chart_of_account} (Phase 5) — the
 * planned amount plus the account's name + type (for display and for normalizing the actual's sign
 * in the variance report). Backs the budget-detail / budget-actuals native query.
 */
public interface BudgetLineView {

  String getAccountCode();

  String getAccountName();

  String getAccountType();

  long getAmountMinor();
}
