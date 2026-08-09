package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.barbershop.ticket.domain.OperatorRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link OperatorRequiredException} to a {@code 409 Conflict} RFC-7807 {@link ProblemDetail}
 * (ADR 0049 P4) — mirrors {@link OutletAccessAdvice}'s style (ADDS to the SHARED {@code
 * libs/security ApiExceptionHandler} catch-all, ordered {@code LOWEST_PRECEDENCE}, so this more
 * specific handler is consulted first).
 *
 * <p>Emitted by {@link id.co.nativeapp.barbershop.ticket.service.OperatorRequiredGuard} when a
 * device (outlet-terminal) actor attempts to ring a ticket with no verified operator session.
 * Detail does not echo any request-derived data.
 */
@RestControllerAdvice
public class OperatorAdvice {

  @ExceptionHandler(OperatorRequiredException.class)
  public ProblemDetail handleOperatorRequired(
      OperatorRequiredException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(OperatorRequiredException.TYPE));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setTitle("Operator session required");
    problem.setDetail(
        "This outlet terminal requires a verified operator (staff PIN) session to ring a ticket.");
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
