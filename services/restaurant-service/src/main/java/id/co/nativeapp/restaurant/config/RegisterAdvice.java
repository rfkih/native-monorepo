package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.register.domain.RegisterSessionAlreadyOpenException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotFoundException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotOpenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the register-session faults (ADR 0036, closing kasir) to RFC-7807 {@link ProblemDetail}
 * responses. Mirrors {@code GiftCardAdvice}/{@code LoyaltyRefAdvice}'s narrow, feature-scoped
 * style.
 *
 * <ul>
 *   <li>{@link RegisterSessionAlreadyOpenException} — a second drawer at the outlet → {@code 409}
 *       ({@code register-session-already-open})
 *   <li>{@link RegisterSessionNotOpenException} — double-close with a NEW key → {@code 409} ({@code
 *       register-session-not-open})
 *   <li>{@link RegisterSessionNotFoundException} — unknown/cross-tenant id → {@code 404} ({@code
 *       register-session-not-found})
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegisterAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(RegisterSessionAlreadyOpenException.class)
  public ProblemDetail handleAlreadyOpen(
      RegisterSessionAlreadyOpenException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "register-session-already-open", request);
    problem.setTitle("A register session is already open at this outlet");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(RegisterSessionNotOpenException.class)
  public ProblemDetail handleNotOpen(
      RegisterSessionNotOpenException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "register-session-not-open", request);
    problem.setTitle("The register session is not open");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(RegisterSessionNotFoundException.class)
  public ProblemDetail handleNotFound(
      RegisterSessionNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "register-session-not-found", request);
    problem.setTitle("Register session not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
