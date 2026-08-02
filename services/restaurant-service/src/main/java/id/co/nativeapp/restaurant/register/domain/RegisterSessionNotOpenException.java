package id.co.nativeapp.restaurant.register.domain;

import java.util.UUID;

/**
 * Closing a session that is not OPEN — a double-close with a DIFFERENT idempotency key (a same-key
 * retry replays to {@code 200} instead). Mapped to {@code 409 Conflict} ({@code
 * register-session-not-open}) by {@code RegisterAdvice}.
 */
public class RegisterSessionNotOpenException extends RuntimeException {

  public RegisterSessionNotOpenException(UUID sessionId, String status) {
    super("register session " + sessionId + " is not OPEN (status: " + status + ")");
  }
}
