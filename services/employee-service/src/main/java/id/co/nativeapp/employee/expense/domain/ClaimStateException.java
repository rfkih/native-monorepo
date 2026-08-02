package id.co.nativeapp.employee.expense.domain;

/**
 * An expense-claim transition was attempted from a state that does not allow it (e.g. approving a
 * DRAFT claim, or submitting one already APPROVED) — the AP-Bill idiom (ADR 0030 §5). Mapped to
 * HTTP 409 (Conflict) by {@code EmployeeApiAdvice}: the request is well-formed, but conflicts with
 * the claim's current state.
 */
public class ClaimStateException extends RuntimeException {

  public ClaimStateException(ClaimStatus current, String attempted) {
    super("Cannot " + attempted + " an expense claim in status " + current);
  }
}
