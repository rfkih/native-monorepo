package id.co.nativeapp.employee.timeoff.domain;

/**
 * A {@code create}-time Idempotency-Key collided with a row belonging to a DIFFERENT employee than
 * the caller (Track P Phase P7 review S1). {@code findByIdempotencyKey} is scoped only by {@code
 * (company_id, idempotency_key)} — RLS already keeps it inside the bound tenant, but nothing
 * previously stopped a foreign employee's row (a genuine key collision, e.g. two different callers
 * reusing a low-entropy client-generated key) from being silently returned to the caller as if it
 * were their own "replay". Mapped to {@code 409} by {@code EmployeeApiAdvice} — never a 200
 * carrying someone else's request.
 */
public class TimeoffIdempotencyKeyConflictException extends RuntimeException {

  public TimeoffIdempotencyKeyConflictException(String resource) {
    super(
        "Idempotency-Key already used for a different employee's "
            + resource
            + " — keys must not be reused across employees");
  }
}
