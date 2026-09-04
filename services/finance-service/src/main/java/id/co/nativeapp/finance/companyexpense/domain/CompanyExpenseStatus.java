package id.co.nativeapp.finance.companyexpense.domain;

/**
 * Lifecycle of a {@link CompanyExpense}: born {@code POSTED} (the form is the document — no draft),
 * voidable once to {@code VOID} (money-side contra only; stock is fixed forward).
 */
public enum CompanyExpenseStatus {
  POSTED,
  VOID
}
