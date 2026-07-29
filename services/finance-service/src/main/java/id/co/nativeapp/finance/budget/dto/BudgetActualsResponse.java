package id.co.nativeapp.finance.budget.dto;

import java.util.List;
import java.util.UUID;

/**
 * The budget-vs-actual variance report for a budget (Phase 5). Each line pairs the planned amount
 * with the account's GL actual for the budget's period ({@code actual} in the account's normal
 * direction — a revenue/liability/equity credit-net, an asset/expense debit-net) and the variance
 * ({@code actual − planned}). All amounts are minor units in {@code currency}.
 */
public record BudgetActualsResponse(
    UUID budgetId,
    String name,
    String period,
    String currency,
    List<ActualLine> lines,
    long totalPlannedMinor,
    long totalActualMinor,
    long totalVarianceMinor,
    boolean usesIllustrativeRules) {

  /** One line: the account, its planned amount, its actual, and the variance (actual − planned). */
  public record ActualLine(
      String accountCode,
      String accountName,
      String accountType,
      long plannedMinor,
      long actualMinor,
      long varianceMinor) {}
}
