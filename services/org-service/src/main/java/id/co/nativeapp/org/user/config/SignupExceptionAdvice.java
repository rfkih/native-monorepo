package id.co.nativeapp.org.user.config;

import id.co.nativeapp.org.user.service.EmailAlreadyExistsException;
import id.co.nativeapp.org.user.service.KeycloakAdminException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 {@link ProblemDetail} advice for the sign-up / user feature exceptions.
 *
 * <p>Handles the two failure modes unique to sign-up:
 *
 * <ul>
 *   <li>{@link EmailAlreadyExistsException} → {@code 409 Conflict} with type {@code
 *       email-already-exists}; the response body does NOT echo the email address (PII / credential
 *       hygiene — HR-6).
 *   <li>{@link KeycloakAdminException} → {@code 502 Bad Gateway} with type {@code
 *       keycloak-admin-unavailable}; the Keycloak URL, client id, and secret are NEVER included in
 *       the response body. The React form maps the stable {@code type} URI to an i18n key.
 * </ul>
 *
 * <p>Validation ({@code 400}), unknown currency / {@code OrgUnitType} ({@code
 * IllegalArgumentException} → {@code 400}), and the non-leaking catch-all ({@code 500}) all come
 * from the shared {@code libs/security} {@code ApiExceptionHandler} — this advice handles only the
 * faults unique to this feature.
 */
@RestControllerAdvice
public class SignupExceptionAdvice {

  private static final Logger log = LoggerFactory.getLogger(SignupExceptionAdvice.class);

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /**
   * A sign-up with an email that already has a Keycloak account → {@code 409 Conflict}. The email
   * itself is NOT included in the detail (PII hygiene — HR-6).
   */
  @ExceptionHandler(EmailAlreadyExistsException.class)
  public ProblemDetail handleEmailAlreadyExists(
      EmailAlreadyExistsException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "email-already-exists"));
    problem.setTitle("Conflict");
    problem.setDetail("An account with this email address already exists.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  /**
   * The Keycloak Admin API failed (unreachable or unexpected error) → {@code 502 Bad Gateway}. The
   * Keycloak URL, client id, and secret are NEVER included in the response body.
   */
  @ExceptionHandler(KeycloakAdminException.class)
  public ProblemDetail handleKeycloakAdminError(
      KeycloakAdminException ex, HttpServletRequest request) {
    // Log the underlying cause server-side (status/URL of the failing Admin call) — the client body
    // stays generic and leaks nothing. Without this a sign-up 502 is undiagnosable in production.
    log.warn("Sign-up failed: Keycloak Admin API error", ex);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
    problem.setType(URI.create(TYPE_BASE + "keycloak-admin-unavailable"));
    problem.setTitle("Bad Gateway");
    problem.setDetail("The identity provider is temporarily unavailable. Please try again later.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  private static void addTraceId(ProblemDetail problem) {
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
  }
}
