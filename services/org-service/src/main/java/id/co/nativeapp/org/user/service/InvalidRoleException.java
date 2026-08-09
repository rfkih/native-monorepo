package id.co.nativeapp.org.user.service;

/**
 * Thrown when a caller supplies a role name that is not in the allowed set (see {@link
 * UserService#ALLOWED_ROLES}: {@code owner}, {@code manager}, {@code cashier}, {@code employee},
 * {@code hr}, {@code accountant}, {@code chef}, {@code waitress}).
 *
 * <p>Mapped to HTTP {@code 400 Bad Request} by {@link
 * id.co.nativeapp.org.user.config.UserExceptionAdvice} with a stable {@code type} URI.
 */
public class InvalidRoleException extends RuntimeException {

  public InvalidRoleException(String role) {
    // Safe: the role value itself is not PII and is suitable for diagnostics.
    super(
        "Invalid role: '"
            + role
            + "'. Allowed values are: owner, manager, cashier, employee, hr, accountant, chef,"
            + " waitress");
  }
}
