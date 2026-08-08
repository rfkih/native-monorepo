package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.mediastorage.UnsupportedImageTypeException;
import id.co.nativeapp.restaurant.menu.domain.InvalidMenuImageException;
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
 * Maps the menu-image faults (ADR 0048, convert-on-write) to RFC-7807 {@link ProblemDetail}
 * responses. Mirrors {@code StocktakeAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link InvalidMenuImageException} — not a base64 data URL / malformed base64 / empty / over
 *       the decoded-size cap → {@code 422} ({@code menu-image-invalid})
 *   <li>{@link UnsupportedImageTypeException} — bytes match no whitelisted image signature
 *       (jpeg/png/webp) or contradict the declared MIME → {@code 422} ({@code
 *       menu-image-unsupported-type}); the shared magic-byte validator's untrusted-header rule
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MenuImageAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(InvalidMenuImageException.class)
  public ProblemDetail handleInvalidImage(
      InvalidMenuImageException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "menu-image-invalid", request);
    problem.setTitle("Menu image payload is invalid");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(UnsupportedImageTypeException.class)
  public ProblemDetail handleUnsupportedType(
      UnsupportedImageTypeException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "menu-image-unsupported-type", request);
    problem.setTitle("Menu image format not supported");
    problem.setDetail(ex.getMessage());
    problem.setProperty("declaredContentType", ex.getDeclaredContentType());
    problem.setProperty("detectedContentType", ex.getDetectedContentType());
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
