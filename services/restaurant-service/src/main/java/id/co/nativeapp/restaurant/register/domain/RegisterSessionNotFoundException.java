package id.co.nativeapp.restaurant.register.domain;

import java.util.UUID;

/**
 * The referenced register session does not exist under the bound tenant (RLS makes a cross-tenant
 * id indistinguishable from a missing one — deliberately). Mapped to {@code 404 Not Found} ({@code
 * register-session-not-found}) by {@code RegisterAdvice}.
 */
public class RegisterSessionNotFoundException extends RuntimeException {

  public RegisterSessionNotFoundException(UUID sessionId) {
    super("register session not found: " + sessionId);
  }
}
