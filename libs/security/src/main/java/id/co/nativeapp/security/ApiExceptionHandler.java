package id.co.nativeapp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single, SHARED RFC 7807 {@link ProblemDetail} advice every Native service inherits (#12),
 * mapping faults to {@code application/problem+json} — never an ad-hoc {@code
 * {status,error,message}} map (ENGINEERING-STANDARDS §1.2). Before this it was copied byte-for-byte
 * into every service's {@code config} package; it now lives once in {@code libs/security} (which
 * already owns the web/edge auto-config and the 403 {@code ProblemDetail} {@link
 * TenantBindingFilter} writes) and is contributed by {@link NativeProblemDetailAutoConfiguration},
 * so a service gets it by dependency, not by copy.
 *
 * <p>Every problem carries a stable kebab-case {@code type} URI ({@code
 * https://errors.nativeapp.id/<slug>}), a numeric {@code status}, a {@code title}, the request
 * {@code instance} path, and — when a trace is in flight — a {@code traceId} property read from the
 * SLF4J {@code MDC} (the {@code trace_id} key the logging pattern already uses), so no heavy OTel
 * dependency is pulled in just to surface it.
 *
 * <p>Three fault shapes are handled, matching the contract the per-service copies pinned:
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — a bean-validation failure on a {@code @Valid}
 *       request body → {@code 400} with a machine-readable {@code errors} list of {@code {field,
 *       message}} (one entry per violated constraint), which the React forms map back to each
 *       field;
 *   <li>{@link IllegalArgumentException} — a value the domain rejects, most importantly a bad/blank
 *       ISO-4217 currency code from {@code libs/money} {@code Money.ofMinor} ({@code
 *       Currency.getInstance} throws {@code IllegalArgumentException} for an unknown code) → {@code
 *       400} with the message as {@code detail}; and
 *   <li>any other {@link Exception} — an unexpected fault → {@code 500} whose {@code detail} is a
 *       generic, non-leaking message (the real exception is logged server-side with the {@code
 *       traceId}, never returned to the client; HR-6).
 * </ul>
 *
 * <p><strong>Composability with service-specific advice.</strong> This advice is ordered at {@link
 * Ordered#LOWEST_PRECEDENCE}, so a service that adds its own {@code @RestControllerAdvice} for a
 * narrower exception (e.g. org-service's {@code TenantAccessDeniedException} → {@code 403}, or
 * finance-service's {@code ConstraintViolationException} → {@code 400}) is consulted first; Spring
 * still resolves the most specific {@code @ExceptionHandler} across all advices, so those keep
 * their mappings while this one owns the shared contract and the catch-all.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Bean-validation failures on a {@code @Valid} request body → 400 with an {@code errors[]}. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleInvalidBody(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", request);
    problem.setTitle("Validation failed");
    problem.setDetail("One or more fields are invalid.");
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(ApiExceptionHandler::toFieldError)
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** A domain-rejected value (e.g. an unknown ISO-4217 currency from Money) → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-argument", request);
    problem.setTitle("Invalid argument");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /**
   * Catch-all → 500 that NEVER leaks the exception message or stack trace to the client (PII/secret
   * risk, HR-6). The real fault is logged server-side with the trace id for diagnosis.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", request);
    problem.setTitle("Internal Server Error");
    problem.setDetail("An unexpected error occurred.");
    log.error("Unhandled exception [traceId={}]", MDC.get("trace_id"), ex);
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }

  private static Map<String, String> toFieldError(FieldError error) {
    String message = error.getDefaultMessage();
    return Map.of("field", error.getField(), "message", message == null ? "invalid" : message);
  }
}
