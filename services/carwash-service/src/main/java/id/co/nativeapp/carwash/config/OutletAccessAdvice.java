package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.outletref.domain.OutletNotAssignedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link OutletNotAssignedException} to a {@code 403 Forbidden} RFC-7807 {@link ProblemDetail}
 * — the outlet-scoping-enforcement fault shape ported from restaurant-service's {@code
 * outletref}/{@code config} handling.
 *
 * <p>This advice ADDS to the SHARED {@code libs/security} {@code ApiExceptionHandler} (which owns
 * the common validation / illegal-argument / catch-all contract) rather than replacing it: the
 * shared advice is ordered {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE
 * LOWEST_PRECEDENCE}, so this more specific handler is consulted first — the exact same pattern
 * {@link NotEntitledAdvice} already follows in this service for {@code NotEntitledException}.
 *
 * <p>Emitted by {@link id.co.nativeapp.carwash.outletref.service.OutletAccessGuard} when a
 * cashier/attendant attempts to ring a ticket at an outlet they are not assigned to. The type URI
 * {@code https://errors.nativeapp.id/outlet-not-assigned} is the stable contract the UI maps to an
 * i18n key. Detail does NOT leak the outlet id (no PII risk, but avoids confirming which outlet ids
 * exist in the tenant) — matching restaurant-service's identical handler.
 */
@RestControllerAdvice
public class OutletAccessAdvice {

  @ExceptionHandler(OutletNotAssignedException.class)
  public ProblemDetail handleOutletNotAssigned(
      OutletNotAssignedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(OutletNotAssignedException.TYPE));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setTitle("Outlet not assigned");
    problem.setDetail("The cashier/attendant is not assigned to this outlet.");
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
