package id.co.nativeapp.finance.companyexpense.domain;

import java.util.UUID;

/**
 * Thrown when a company-expense id is not visible in the bound tenant (missing, or another tenant's
 * — RLS makes the two indistinguishable, deliberately). Maps to {@code 404} via {@code
 * CompanyExpenseAdvice} with a generic detail (no existence disclosure).
 */
public class CompanyExpenseNotFoundException extends RuntimeException {

  public CompanyExpenseNotFoundException(UUID expenseId) {
    super("no such company expense: " + expenseId);
  }
}
