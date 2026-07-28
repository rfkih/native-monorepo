package id.co.nativeapp.org.user.service;

/**
 * Thrown when an invite is attempted with a {@code username} that already has a Keycloak account in
 * the realm (the login identifier must be unique).
 *
 * <p>Mapped to HTTP {@code 409 Conflict} by {@link
 * id.co.nativeapp.org.user.config.SignupExceptionAdvice} with a stable {@code type} URI the React
 * form can map to an i18n key.
 */
public class UsernameAlreadyExistsException extends RuntimeException {

  private final String username;

  public UsernameAlreadyExistsException(String username) {
    super("A Keycloak account already exists for this username");
    this.username = username;
  }

  /** The username that is already taken (for logging; NEVER leaked to the HTTP response). */
  public String getUsername() {
    return username;
  }
}
