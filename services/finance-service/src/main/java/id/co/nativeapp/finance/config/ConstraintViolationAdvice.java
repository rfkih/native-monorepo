package id.co.nativeapp.finance.config;

import id.co.nativeapp.finance.fx.MissingFxRateException;
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
 * The finance-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the one fault shape unique
 * to this service that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which owns the
 * common {@code @Valid}-body validation / illegal-argument / catch-all contract, #12) does not
 * handle.
 *
 * <p>{@link ConstraintViolationException} — a bean-validation failure on a request
 * <em>parameter</em> (e.g. a malformed {@code period} query param on {@code GET /api/v1/revenue},
 * where the {@code @Validated} {@code @Pattern} on the method argument is violated) → {@code 400}
 * with the same machine-readable {@code errors} list of {@code {field, message}} the shared
 * body-validation handler emits, under the same stable {@code validation-failed} {@code type}. The
 * shared advice handles {@code MethodArgumentNotValidException} (the {@code @Valid} body case), but
 * a method-parameter violation surfaces as a different exception type, so finance keeps this thin
 * handler.
 *
 * <p>This advice ADDS to the shared one rather than replacing it: the shared advice is ordered
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE}, so this more
 * specific handler is consulted first and Spring resolves the most specific
 * {@code @ExceptionHandler} across both. Unknown-currency / bad-period {@code
 * IllegalArgumentException} → {@code 400} and the non-leaking catch-all {@code 500} still come from
 * the shared advice, so they are no longer duplicated here.
 *
 * <p>It also maps {@link MissingFxRateException} → {@code 422 Unprocessable Entity}: a presentation
 * conversion was requested but no FX rate (direct or USD-pivot) resolves for the period, so the
 * server CANNOT satisfy the presentation currency. Fail-loud — the endpoint never invents a rate or
 * returns the native figure mislabelled as converted.
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
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream().map(ConstraintViolationAdvice::toViolation).toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * No resolvable FX rate for a requested presentation conversion → {@code 422}. The well-formed
   * request cannot be satisfied for this period/currency; the native figures are NOT returned
   * mislabelled as converted (fail-loud, never invent a rate).
   */
  @ExceptionHandler(MissingFxRateException.class)
  public ProblemDetail handleMissingFxRate(MissingFxRateException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "fx-rate-unavailable"));
    problem.setTitle("FX rate unavailable");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
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
