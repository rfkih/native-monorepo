package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.barbershop.catalog.domain.CatalogItemNotFoundException;
import id.co.nativeapp.barbershop.catalog.domain.StaffProfileWriteForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The catalog-feature-specific RFC 7807 {@link ProblemDetail} advice — the fault shapes the shared
 * {@code libs/security ApiExceptionHandler} does not own, mirroring {@link NotEntitledAdvice} /
 * {@link OutletAccessAdvice}'s style in this same package.
 *
 * <ul>
 *   <li>{@link CatalogItemNotFoundException} — an unknown or cross-tenant {@code service_item} /
 *       {@code service_addon} / {@code staff_profile} id (RLS makes a cross-tenant id
 *       indistinguishable from unknown — no existence disclosure) → {@code 404}.
 *   <li>{@link DataIntegrityViolationException} — most notably a duplicate {@code staff_profile}
 *       {@code (company_id, business_id, display_label)} → {@code 409}, rather than leaking a raw
 *       {@code 500}.
 * </ul>
 */
@RestControllerAdvice
public class CatalogAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(CatalogItemNotFoundException.class)
  public ProblemDetail handleNotFound(CatalogItemNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "catalog-item-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such catalog item is accessible.");
    return decorate(problem, request);
  }

  @ExceptionHandler(StaffProfileWriteForbiddenException.class)
  public ProblemDetail handleStaffWriteForbidden(
      StaffProfileWriteForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "staff-profile-write-forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail("Staff profile changes require the owner or manager role.");
    return decorate(problem, request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleConflict(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "catalog-item-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(
        "The operation conflicted with an existing row (e.g. a duplicate staff display label).");
    return decorate(problem, request);
  }

  private static ProblemDetail decorate(ProblemDetail problem, HttpServletRequest request) {
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
