package id.co.nativeapp.entitlement.config;

import id.co.nativeapp.entitlement.entitlement.EntitlementNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The entitlement-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the one fault shape
 * unique to this service that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which
 * owns the common validation / illegal-argument / catch-all contract, #12) does not handle.
 *
 * <p>{@link EntitlementNotFoundException} — a revoke targeting a module the tenant was never
 * granted → {@code 404}. This advice ADDS to the shared one rather than replacing it: the shared
 * advice is ordered {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE},
 * so this more specific handler is consulted first. Validation ({@code 400}), unknown-module
 * ({@code UnknownModuleException} extends {@code IllegalArgumentException} → {@code 400}), and the
 * non-leaking catch-all ({@code 500}) all come from the shared advice, so they are not duplicated
 * here. This is NOT a copy of any platform config — it is one narrow service-specific mapping, the
 * same pattern org-service's {@code TenantAccessDeniedAdvice} follows.
 *
 * <p>The {@code traceId} (from the SLF4J {@code MDC} {@code trace_id} key) and the stable
 * kebab-case {@code type} URI are emitted the same way the shared advice does, so the {@code
 * ProblemDetail} shape stays uniform and the React forms can map the {@code type} to an i18n key.
 */
@RestControllerAdvice
public class EntitlementNotFoundAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A revoke of a module the tenant has no entitlement for → 404. */
  @ExceptionHandler(EntitlementNotFoundException.class)
  public ProblemDetail handleNotFound(EntitlementNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "entitlement-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
