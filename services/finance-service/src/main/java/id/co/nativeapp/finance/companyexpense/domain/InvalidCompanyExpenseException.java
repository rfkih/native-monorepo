package id.co.nativeapp.finance.companyexpense.domain;

/**
 * The submitted expense violates a kind-shape rule (GENERAL with lines, INVENTORY without lines, a
 * non-positive amount, a line/total mismatch, too many lines, …). Maps to {@code 422} via {@code
 * CompanyExpenseAdvice}; the message is user-safe.
 */
public class InvalidCompanyExpenseException extends RuntimeException {

  public InvalidCompanyExpenseException(String message) {
    super(message);
  }
}
