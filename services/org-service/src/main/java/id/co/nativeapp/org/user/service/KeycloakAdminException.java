package id.co.nativeapp.org.user.service;

/**
 * Unchecked exception thrown when the Keycloak Admin REST API is unreachable, returns a non-2xx
 * status for an unexpected reason, or encounters an I/O error.
 *
 * <p>Mapped to HTTP {@code 502 Bad Gateway} by {@link
 * id.co.nativeapp.org.user.config.SignupExceptionAdvice} so the client knows the upstream identity
 * provider failed. The Keycloak URL and client secret are NEVER included in the message — only a
 * safe, non-leaking diagnostic summary.
 */
public class KeycloakAdminException extends RuntimeException {

  public KeycloakAdminException(String message) {
    super(message);
  }

  public KeycloakAdminException(String message, Throwable cause) {
    super(message, cause);
  }
}
