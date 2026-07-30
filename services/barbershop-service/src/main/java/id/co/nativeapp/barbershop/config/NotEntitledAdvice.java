package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.barbershop.entitlement.domain.NotEntitledException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The barbershop-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the one fault shape
 * unique to this vertical that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which
 * owns the common validation / illegal-argument / catch-all contract, #12) does not handle.
 *
 * <p>{@link NotEntitledException} — a catalog/ticket write by a company that is NOT entitled to the
 * {@code barbershop} module → {@code 403} (a tenant/role denial, ENGINEERING-STANDARDS §1.1). This
 * advice ADDS to the shared one rather than replacing it: the shared advice is ordered {@link
 * org.springframework.core.Ordered#LOWEST_PRECEDENCE LOWEST_PRECEDENCE}, so this more specific
 * handler is consulted first. Validation ({@code 400}) and the non-leaking catch-all ({@code 500})
 * come from the shared advice, so they are not duplicated here. This is NOT a copy of any platform
 * config — it is one narrow service-specific mapping, the same pattern entitlement-service's {@code
 * EntitlementNotFoundAdvice} and carwash-service's identically-named advice follow.
 *
 * <p>The {@code traceId} (from the SLF4J {@code MDC} {@code traceId} key) and the stable kebab-case
 * {@code type} URI are emitted the same way the shared advice does, so the {@code ProblemDetail}
 * shape stays uniform and the React forms can map the {@code type} to an i18n key.
 */
@RestControllerAdvice
public class NotEntitledAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A catalog/ticket write by a company not entitled to the barbershop module → 403. */
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
