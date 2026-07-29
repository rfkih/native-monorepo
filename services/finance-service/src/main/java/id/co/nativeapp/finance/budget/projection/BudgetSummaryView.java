package id.co.nativeapp.finance.budget.projection;

import java.util.UUID;

/**
 * Read projection over a {@code budget} list row (Phase 5) — the header plus a rolled-up line count
 * and total planned amount (from {@code budget_line}). Backs the budget-list native query.
 */
public interface BudgetSummaryView {

  UUID getId();

  String getName();

  String getPeriod();

  String getCurrency();

  long getLineCount();

  long getTotalPlannedMinor();
}
