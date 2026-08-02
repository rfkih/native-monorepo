package id.co.nativeapp.employee.expense.projection;

/**
 * One status's claim count for the org-unit hub's Expenses tab rollup (Phase E8) — native-query
 * read model, {@code ExpenseClaimRepository#summarizeByStatus}. Covers EVERY {@link
 * id.co.nativeapp.employee.expense.domain.ClaimStatus}, not just {@code APPROVED}/{@code
 * REIMBURSED} — so a manager sees how many claims are still pending a decision alongside the
 * recognised spend {@link OrgUnitExpenseCategoryTotalView} carries.
 */
public interface OrgUnitExpenseStatusCountView {

  String getStatus();

  long getCount();
}
