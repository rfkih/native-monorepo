package id.co.nativeapp.employee.expense.dto;

import java.util.List;

/**
 * {@code GET /api/v1/expense-claims/summary} response (Phase E8) — the org-unit hub's Expenses tab
 * rollup: a per-category breakdown, claim counts by status, and the approved+reimbursed grand total
 * the category breakdown already implies. {@code byCategory} and {@code byStatus} share the SAME
 * org-unit scope, but read the SAME {@code period} value against TWO DIFFERENT date columns (W1, E8
 * review — a reconciliation gap, since fixed): they are NOT two views of the same money.
 *
 * <p><strong>{@code byCategory}/{@code approvedReimbursedTotalMinor} — RECONCILES to the
 * GL.</strong> Counts ONLY {@code APPROVED}/{@code REIMBURSED} claims (the two states in which the
 * expense is recognised on the books, ADR 0030 §2), {@code period}-filtered on {@code approved_at}
 * — EXACTLY the period {@code ExpenseClaimPostingWriter} books into (it keys off {@code
 * approvedAt}, never {@code expense_date}). This is the figure that agrees with the org-unit's
 * P&amp;L rollup for the SAME period (zero finance change, E8 spec).
 *
 * <p><strong>{@code byStatus} — OPERATIONAL only, does NOT reconcile.</strong> Counts EVERY status
 * (so a manager sees how many claims are pending a decision), {@code period}-filtered on {@code
 * expense_date} — the incurred date, not the recognition date (most rows have no {@code
 * approved_at} at all). A claim incurred in one month but approved in the next therefore shows
 * under DIFFERENT months in {@code byCategory} vs {@code byStatus} for the SAME requested {@code
 * period} — by design, not a bug. Never present {@code byStatus} counts as if they sum to a GL
 * total.
 *
 * @param byCategory per-category totals (approval-period, reconciles to the GL), largest first;
 *     empty if nothing is recognised in scope for the period
 * @param byStatus claim counts by status (incurred-period, operational only); empty if no claim
 *     exists in scope for the period
 * @param approvedReimbursedTotalMinor the sum of every {@code byCategory} row's {@code totalMinor}
 *     (zero when {@code byCategory} is empty) — reconciles to the GL, same caveat as {@code
 *     byCategory}
 * @param currency the tenant's established expense-claim currency, or {@code null} when {@code
 *     byCategory} is empty — never invented (mirrors {@code UnitPnlResponse})
 */
public record OrgUnitExpenseSummaryResponse(
    List<CategoryTotal> byCategory,
    List<StatusCount> byStatus,
    long approvedReimbursedTotalMinor,
    String currency) {

  /** One category's recognised (APPROVED/REIMBURSED) spend. */
  public record CategoryTotal(String categoryName, long totalMinor, String currency) {}

  /** One status's claim count. */
  public record StatusCount(String status, long count) {}
}
