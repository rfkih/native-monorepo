package id.co.nativeapp.restaurant.register.domain;

/**
 * A replayed {@code Idempotency-Key} whose request payload DIFFERS from the original (engineering
 * standard §1.3: same key + different payload → 409). Prevents a client bug from silently masking a
 * changed drawer count or a cross-outlet key reuse behind a 200 replay. Mapped to {@code 409
 * Conflict} ({@code register-session-idempotency-key-conflict}) by {@code RegisterAdvice}.
 */
public class RegisterSessionIdempotencyKeyConflictException extends RuntimeException {

  public RegisterSessionIdempotencyKeyConflictException(String message) {
    super(message);
  }
}
