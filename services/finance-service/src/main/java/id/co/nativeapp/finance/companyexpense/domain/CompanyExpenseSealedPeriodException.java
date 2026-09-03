package id.co.nativeapp.finance.companyexpense.domain;

/**
 * The expense's {@code occurred_at} falls in a period the tenant has already FILED a tax return for
 * (the ADR 0028 sealed-period rule). A user-facing form fails at input rather than quarantining:
 * maps to {@code 422} via {@code CompanyExpenseAdvice}.
 */
public class CompanyExpenseSealedPeriodException extends RuntimeException {

  public CompanyExpenseSealedPeriodException(String period) {
    super("period " + period + " is sealed by a filed tax return; the expense cannot post into it");
  }
}
