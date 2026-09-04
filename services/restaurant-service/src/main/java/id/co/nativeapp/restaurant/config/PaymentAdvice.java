package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.payment.domain.PaymentNotReversibleException;
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
 * Maps payment reversal (refund/void) state conflicts to RFC-7807 {@link ProblemDetail} responses.
 * Mirrors {@code ChannelAdvice}/{@code OrderAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link PaymentNotReversibleException} — a refund/void of a payment not in a reversible
 *       state (most commonly an already-returned sale) → {@code 409} ({@code
 *       payment-not-reversible}). Without this handler the exception (an {@link
 *       IllegalStateException}) falls to the shared catch-all {@code 500}, mislabeling an expected,
 *       permanent rejection as a transient server fault and logging a spurious internal error
 *       (code-review W1).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(PaymentNotReversibleException.class)
  public ProblemDetail handleNotReversible(
      PaymentNotReversibleException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "payment-not-reversible", request);
    problem.setTitle("Payment not reversible");
    problem.setDetail(ex.getMessage());
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
