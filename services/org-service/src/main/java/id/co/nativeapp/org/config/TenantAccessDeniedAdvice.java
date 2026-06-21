package id.co.nativeapp.org.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.NoSuchElementException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The org-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the one fault shape unique to
 * this service that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which owns the
 * common validation / illegal-argument / catch-all contract, #12) does not handle.
 *
 * <p>{@link TenantAccessDeniedException} — a path {@code companyId} that differs from the bound
 * tenant scope (a confused-deputy attempt) → {@code 403}. This advice ADDS to the shared one rather
 * than replacing it: the shared advice is ordered {@link
 * org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE}, so this more specific
 * handler is consulted first and Spring resolves the most specific {@code @ExceptionHandler} across
 * both advices. Validation ({@code 400}), unknown-currency / unknown-{@code OrgUnitType} ({@code
 * IllegalArgumentException} → {@code 400}), and the non-leaking catch-all ({@code 500}) all come
 * from the shared advice, so they are no longer duplicated here.
 *
 * <p>The {@code traceId} (from the SLF4J {@code MDC} {@code traceId} key) and the stable kebab-case
 * {@code type} URI are emitted the same way the shared advice does, so the {@code ProblemDetail}
 * shape is uniform across every fault and the React forms can map the {@code type} to an i18n key.
 */
@RestControllerAdvice
public class TenantAccessDeniedAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A path {@code companyId} that differs from the bound tenant (confused deputy) → 403. */
  @ExceptionHandler(TenantAccessDeniedException.class)
  public ProblemDetail handleTenantAccessDenied(
      TenantAccessDeniedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "tenant-mismatch"));
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }

  /**
   * A resource that does not exist for the bound tenant → 404. Used by {@code GET
   * /api/v1/companies/current} when no company row exists for the bound {@code company_id} (e.g. a
   * newly provisioned tenant that has not yet been bootstrapped). The exception message is
   * server-diagnostic English; it is never localised here (HR-9).
   */
  @ExceptionHandler(NoSuchElementException.class)
  public ProblemDetail handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "not-found"));
    problem.setTitle("Not Found");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
