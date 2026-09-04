package id.co.nativeapp.restaurant.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The restaurant-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice for a bean-validation
 * failure on a request <em>parameter</em> (e.g. a malformed {@code period} query param on {@code
 * GET /api/v1/sales/channel-summary}, where the {@code @Validated} {@code @Pattern} on the method
 * argument is violated) — mirrors finance-service's {@code ConstraintViolationAdvice}.
 *
 * <p>The SHARED {@code libs/security} {@code ApiExceptionHandler} (fleet-wide) handles {@code
 * MethodArgumentNotValidException} (the {@code @Valid} body case), but a method-parameter violation
 * surfaces as {@link ConstraintViolationException}, a different exception type its catch-all would
 * otherwise map to an opaque {@code 500} — so this thin, service-specific advice maps it to {@code
 * 400} with the same machine-readable {@code errors} list of {@code {field, message}} under the
 * same stable {@code validation-failed} {@code type}, ADDING to the shared advice (which stays
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE}) rather than
 * replacing it.
 */
@RestControllerAdvice
public class ConstraintViolationAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Bean-validation failures on a request parameter (e.g. {@code period}) → 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "validation-failed"));
    problem.setTitle("Validation failed");
    problem.setDetail("One or more parameters are invalid.");
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream().map(ConstraintViolationAdvice::toViolation).toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  private static Map<String, String> toViolation(ConstraintViolation<?> violation) {
    String message = violation.getMessage();
    return Map.of(
        "field",
        violation.getPropertyPath().toString(),
        "message",
        message == null ? "invalid" : message);
  }
}
