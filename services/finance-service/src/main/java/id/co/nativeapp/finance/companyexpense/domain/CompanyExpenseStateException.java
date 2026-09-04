package id.co.nativeapp.finance.companyexpense.domain;

/**
 * An illegal lifecycle transition on a {@code CompanyExpense} (e.g. voiding an already-VOID
 * expense). Maps to {@code 409 Conflict} via {@code CompanyExpenseAdvice}.
 */
public class CompanyExpenseStateException extends RuntimeException {

  public CompanyExpenseStateException(String message) {
    super(message);
  }
}
