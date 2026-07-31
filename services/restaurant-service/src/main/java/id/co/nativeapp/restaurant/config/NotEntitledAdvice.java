package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.entitlement.domain.NotEntitledException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The restaurant-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice for {@link
 * NotEntitledException} — the one fault shape Phase 6 (ADR 0029) introduces that the SHARED {@code
 * libs/security} {@code ApiExceptionHandler} (which owns the common validation / illegal-argument /
 * catch-all contract) does not handle.
 *
 * <p>{@link NotEntitledException} — a self-order-create by a company NOT entitled to the {@code
 * self_order} module -> {@code 403} (a tenant/role denial, ENGINEERING-STANDARDS §1.1). This advice
 * ADDS to the shared one rather than replacing it: the shared advice is ordered {@link
 * org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE}, so this more specific
 * handler is consulted first. Mirrors barbershop-service's/carwash-service's identically-named
 * advice exactly.
 */
@RestControllerAdvice
public class NotEntitledAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A self-order-create by a company not entitled to the self_order module -> 403. */
  @ExceptionHandler(NotEntitledException.class)
  public ProblemDetail handleNotEntitled(NotEntitledException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "module-not-entitled"));
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
