package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.ticket.domain.MixedCurrencyException;
import id.co.nativeapp.carwash.ticket.domain.PaymentNotPendingException;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The ticket-feature-specific RFC 7807 {@link ProblemDetail} advice — the fault shapes the shared
 * {@code libs/security ApiExceptionHandler} does not own, mirroring {@link NotEntitledAdvice} /
 * {@link OutletAccessAdvice}'s style in this same package. Unknown/inactive/cross-business item and
 * a cash short-tender ride the shared {@code IllegalArgumentException → 400} mapping already.
 *
 * <ul>
 *   <li>{@link TicketNotFoundException} — an unknown or cross-tenant ticket id → {@code 404}.
 *   <li>{@link MixedCurrencyException} — a checkout/quote whose resolved items span more than one
 *       currency → {@code 422 Unprocessable Entity}.
 *   <li>{@link PaymentNotPendingException} — a capture attempt on a payment that is neither {@code
 *       CAPTURED} (handled separately, no error) nor {@code PENDING} → {@code 409 Conflict}.
 * </ul>
 */
@RestControllerAdvice
public class TicketAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(TicketNotFoundException.class)
  public ProblemDetail handleNotFound(TicketNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "ticket-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such ticket is accessible.");
    return decorate(problem, request);
  }

  @ExceptionHandler(MixedCurrencyException.class)
  public ProblemDetail handleMixedCurrency(MixedCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "ticket-mixed-currency"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(PaymentNotPendingException.class)
  public ProblemDetail handlePaymentNotPending(
      PaymentNotPendingException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "ticket-payment-not-pending"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
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
