package id.co.nativeapp.org.user.service;

/**
 * Thrown when a sign-up is attempted with an email address that already has a Keycloak account in
 * the realm.
 *
 * <p>Mapped to HTTP {@code 409 Conflict} by {@link
 * id.co.nativeapp.org.user.config.SignupExceptionAdvice} with a stable {@code type} URI the React
 * form can map to an i18n key.
 */
public class EmailAlreadyExistsException extends RuntimeException {

  private final String email;

  public EmailAlreadyExistsException(String email) {
    super("A Keycloak account already exists for this email address");
    this.email = email;
  }

  /** The email address that is already taken (for logging; NEVER leaked to the HTTP response). */
  public String getEmail() {
    return email;
  }
}
