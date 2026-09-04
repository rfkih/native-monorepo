package id.co.nativeapp.finance.companyexpense.domain;

/**
 * The submitted {@code Idempotency-Key} already recorded a DIFFERENT payload — the retry contract
 * only replays the SAME submit. Maps to {@code 409} via {@code CompanyExpenseAdvice}.
 */
public class CompanyExpenseIdempotencyConflictException extends RuntimeException {

  public CompanyExpenseIdempotencyConflictException(String idempotencyKey) {
    super("Idempotency-Key '" + idempotencyKey + "' was already used with a different payload");
  }
}
