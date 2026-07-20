package id.co.nativeapp.org.user.service;

/**
 * Thrown when the caller attempts to perform an operation on themselves that would lock them out of
 * the system — specifically:
 *
 * <ul>
 *   <li>Disabling their own account (via {@code DELETE /api/v1/users/{id}} or {@code PATCH
 *       /api/v1/users/{id}} with {@code enabled=false}), or
 *   <li>Demoting themselves away from the {@code owner} role (via {@code PATCH /api/v1/users/{id}}
 *       with a different role).
 * </ul>
 *
 * <p>Mapped to HTTP {@code 409 Conflict} by {@link
 * id.co.nativeapp.org.user.config.UserExceptionAdvice} with a stable {@code type} URI so the client
 * can show a localized message.
 */
public class SelfLockoutException extends RuntimeException {

  public SelfLockoutException(String reason) {
    super(reason);
  }
}
