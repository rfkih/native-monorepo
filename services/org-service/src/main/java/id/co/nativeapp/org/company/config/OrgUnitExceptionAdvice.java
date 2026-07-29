package id.co.nativeapp.org.company.config;

import id.co.nativeapp.org.company.service.OrgUnitHasDataException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 {@link ProblemDetail} advice for org-unit faults.
 *
 * <ul>
 *   <li>{@link OrgUnitHasDataException} → {@code 409 Conflict}, type {@code org-unit-has-data}: a
 *       permanent delete was attempted on a unit that still has an assigned login (deactivate
 *       instead). The React tree maps the stable {@code type} URI to an i18n key.
 * </ul>
 *
 * <p>An unknown-unit {@link IllegalArgumentException} (→ {@code 400}) is left to the shared {@code
 * libs/security} {@code ApiExceptionHandler}, matching the sibling advices in this service.
 */
@RestControllerAdvice
public class OrgUnitExceptionAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A permanent delete of a non-empty org unit → {@code 409 Conflict}. */
  @ExceptionHandler(OrgUnitHasDataException.class)
  public ProblemDetail handleOrgUnitHasData(
      OrgUnitHasDataException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "org-unit-has-data"));
    problem.setTitle("Conflict");
    problem.setDetail(
        "This unit has (or previously had) an assigned login and cannot be permanently deleted."
            + " Deactivate it instead.");
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
