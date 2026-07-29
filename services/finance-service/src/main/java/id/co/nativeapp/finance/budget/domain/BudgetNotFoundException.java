package id.co.nativeapp.finance.budget.domain;

import java.util.UUID;

/**
 * Thrown when a {@link Budget} id is not visible in the bound tenant (RLS-scoped). Mapped to {@code
 * 404} with a generic detail — a foreign-tenant id is indistinguishable from a missing one, so there
 * is no existence disclosure (mirrors {@code BillNotFoundException}).
 */
public class BudgetNotFoundException extends RuntimeException {

  public BudgetNotFoundException(UUID budgetId) {
    super("budget not found: " + budgetId);
  }
}
