package id.co.nativeapp.org.user.service;

/**
 * Thrown when a caller attempts a user-management action on a target that outranks them — e.g. a
 * {@code manager} resetting an {@code owner}'s (or another manager's) password. The gateway's role
 * gate is coarse (owner + manager both reach {@code /api/v1/users/**}); this is the fine-grained
 * role-hierarchy guard that keeps a manager from taking over an owner account.
 *
 * <p>Mapped to HTTP {@code 403 Forbidden} by {@link
 * id.co.nativeapp.org.user.config.UserExceptionAdvice}.
 */
public class InsufficientPrivilegeException extends RuntimeException {

  public InsufficientPrivilegeException(String message) {
    super(message);
  }
}
