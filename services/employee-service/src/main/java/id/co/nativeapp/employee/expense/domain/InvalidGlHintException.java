package id.co.nativeapp.employee.expense.domain;

/**
 * An expense category was created/updated with a {@code gl_hint} outside the seeded whitelist
 * ({@link ExpenseCategory#GL_HINT_WHITELIST}). An unrecognised hint would otherwise quarantine
 * every claim in that category to finance's suspense account silently (ADR 0030) — rejecting it at
 * category admin time surfaces the mistake immediately instead. Mapped to HTTP 422 (Unprocessable
 * Entity) by {@code EmployeeApiAdvice}: the request is well-formed JSON, but the value itself is
 * semantically invalid.
 */
public class InvalidGlHintException extends RuntimeException {

  public InvalidGlHintException(String glHint) {
    super(
        "Unknown gl_hint '"
            + glHint
            + "'; must be one of the seeded set (a new hint requires a finance mapping_rule seed"
            + " first — ask integration-engineer / tech-lead)");
  }
}
