package id.co.nativeapp.org.user.service;

/**
 * Thrown when a target user cannot be found in Keycloak — either because the user id does not exist
 * at all, or because it belongs to a different company (cross-tenant access attempt).
 *
 * <p><strong>Security note.</strong> Cross-tenant targets produce the same 404 as a genuinely
 * absent user, so a caller cannot enumerate the existence of other tenants' user ids (the
 * cross-tenant guard returns NOT FOUND, not FORBIDDEN). The caller receives no information beyond
 * "user not found".
 *
 * <p>Mapped to HTTP {@code 404 Not Found} by {@link
 * id.co.nativeapp.org.user.config.UserExceptionAdvice} with a stable {@code type} URI.
 */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(String userId) {
    // Safe message: only the ID (a UUID), no PII.
    super("User not found: " + userId);
  }
}
