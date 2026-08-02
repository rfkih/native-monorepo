package id.co.nativeapp.employee.expense.dto;

import java.util.List;

/**
 * {@code GET /api/v1/expense-claims/summary} response (Phase E8) — the org-unit hub's Expenses tab
 * rollup: a per-category breakdown, claim counts by status, and the approved+reimbursed grand total
 * the category breakdown already implies. Both {@code byCategory} and {@code byStatus} share the
 * SAME org-unit/period scope the caller requested.
 *
 * <p>{@code byCategory} counts ONLY {@code APPROVED}/{@code REIMBURSED} claims (the two states in
 * which the expense is recognised on the books, ADR 0030 §2) — the same subset finance's per-unit
 * P&amp;L rollup carries for these org units (zero finance change, E8 spec), so this tile and the
 * Overview tab's expense figure agree. {@code byStatus} counts EVERY status, so a manager also sees
 * how many claims are still pending a decision.
 *
 * @param byCategory per-category totals, largest first; empty if nothing is recognised in scope
 * @param byStatus claim counts by status; empty if no claim exists in scope
 * @param approvedReimbursedTotalMinor the sum of every {@code byCategory} row's {@code totalMinor}
 *     (zero when {@code byCategory} is empty)
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
