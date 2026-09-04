package id.co.nativeapp.restaurant.register.domain;

import java.util.UUID;

/**
 * Correcting (ADR 0064) a register session that is not CLOSED — a correction amends an
 * already-final close, so an OPEN session has nothing to correct (close it first). Mapped to {@code
 * 409 Conflict} ({@code register-session-not-closed}) by {@code RegisterAdvice}.
 */
public class RegisterSessionNotClosedException extends RuntimeException {

  public RegisterSessionNotClosedException(UUID sessionId, String status) {
    super("register session " + sessionId + " is not CLOSED (status: " + status + ")");
  }
}
