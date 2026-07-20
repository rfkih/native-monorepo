package id.co.nativeapp.org.user.config;

import id.co.nativeapp.org.user.service.InvalidRoleException;
import id.co.nativeapp.org.user.service.SelfLockoutException;
import id.co.nativeapp.org.user.service.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 {@link ProblemDetail} advice for the user-management feature exceptions.
 *
 * <p>Maps the three user-management faults to problem responses with stable {@code type} URIs that
 * the React client can map to i18n keys:
 *
 * <ul>
 *   <li>{@link UserNotFoundException} → {@code 404 Not Found} with type {@code user-not-found}.
 *       Used for both genuinely absent users AND cross-tenant access attempts (anti-enumeration —
 *       the caller cannot distinguish "doesn't exist" from "belongs to another tenant").
 *   <li>{@link InvalidRoleException} → {@code 400 Bad Request} with type {@code invalid-role}.
 *   <li>{@link SelfLockoutException} → {@code 409 Conflict} with type {@code self-lockout}.
 * </ul>
 *
 * <p>A generic {@link IllegalArgumentException} from the service layer (e.g. a both-null PATCH) is
 * deliberately NOT handled here — the shared {@code libs/security} {@code ApiExceptionHandler} owns
 * it (→ {@code 400}, type {@code invalid-argument}), and re-declaring it here would tie the advice
 * order and make the response {@code type} non-deterministic. Same convention as the sibling {@code
 * SignupExceptionAdvice} / {@code TenantAccessDeniedAdvice}.
 *
 * <p><strong>PII / security hygiene.</strong> The response bodies never contain email addresses,
 * passwords, or any information that would help a caller enumerate cross-tenant user ids. The
 * {@code type} URI for {@code user-not-found} is deliberately identical for "not found" and
 * "belongs to another tenant" (anti-enumeration).
 */
@RestControllerAdvice
public class UserExceptionAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /**
   * A user id that does not exist in the caller's company (genuinely absent OR cross-tenant) →
   * {@code 404 Not Found}. The response is identical for both cases (anti-enumeration).
   */
  @ExceptionHandler(UserNotFoundException.class)
  public ProblemDetail handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "user-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("The requested user does not exist.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  /** A role value outside the allowed set (owner/manager/cashier) → {@code 400 Bad Request}. */
  @ExceptionHandler(InvalidRoleException.class)
  public ProblemDetail handleInvalidRole(InvalidRoleException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "invalid-role"));
    problem.setTitle("Bad Request");
    problem.setDetail("The provided role is not valid. Allowed values: owner, manager, cashier.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  /** An attempted self-lockout (disable self or demote own owner role) → {@code 409 Conflict}. */
  @ExceptionHandler(SelfLockoutException.class)
  public ProblemDetail handleSelfLockout(SelfLockoutException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "self-lockout"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
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
