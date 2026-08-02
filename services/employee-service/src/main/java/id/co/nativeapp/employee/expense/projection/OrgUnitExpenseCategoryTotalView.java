package id.co.nativeapp.employee.expense.projection;

/**
 * One category's summed spend for the org-unit hub's Expenses tab rollup (Phase E8) — native-query
 * read model, {@code ExpenseClaimRepository#summarizeByCategory}. Only {@code APPROVED}/{@code
 * REIMBURSED} claims are summed (the two states in which the expense is recognised on the books,
 * ADR 0030 §2), grouped by category name AND currency (the {@code LinkedClaimTotalView} defensive
 * idiom — v1 is single-currency per tenant in practice, but grouping by currency too means a
 * hypothetical mixed-currency tenant surfaces as separate rows rather than a silently corrupted
 * cross-currency sum, rule 8).
 */
public interface OrgUnitExpenseCategoryTotalView {

  String getCategoryName();

  long getTotalMinor();

  String getCurrency();
}
