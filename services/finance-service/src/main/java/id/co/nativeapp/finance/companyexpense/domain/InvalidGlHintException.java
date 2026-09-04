package id.co.nativeapp.finance.companyexpense.domain;

/**
 * The submitted {@code gl_hint} is not one of the whitelisted values ({@code ""}, {@code cogs},
 * {@code supplies}, {@code utilities} — mirroring employee-service's {@code
 * ExpenseCategory.GL_HINT_WHITELIST}). A user-facing form fails at input (422) instead of landing
 * on the 9999 suspense account; the suspense fail-safe remains for event-consumer paths.
 */
public class InvalidGlHintException extends RuntimeException {

  public InvalidGlHintException(String glHint) {
    super("unknown gl_hint '" + glHint + "'; expected one of \"\", cogs, supplies, utilities");
  }
}
