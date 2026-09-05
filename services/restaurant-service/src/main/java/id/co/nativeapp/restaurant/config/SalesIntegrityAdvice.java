package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.integrity.domain.MixedCurrencyLeakReportException;
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
 * Maps the sales-leak report's one fault (ADR 0074) to an RFC-7807 {@link ProblemDetail}. Mirrors
 * {@link StocktakeAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link MixedCurrencyLeakReportException} — the outlet's figures do not share a currency →
 *       {@code 422} ({@code sales-integrity-mixed-currency})
 * </ul>
 *
 * <p>422 rather than 500 because the request is well-formed and the server is healthy; what cannot
 * be processed is the DATA, and the response says so precisely enough to act on. Failing here at
 * all is the point: an estimate of money lost, reached by adding two different kinds of money
 * together, would be acted on as though it meant something.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SalesIntegrityAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(MixedCurrencyLeakReportException.class)
  public ProblemDetail handleMixedCurrency(
      MixedCurrencyLeakReportException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "sales-integrity-mixed-currency"));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setTitle("Mixed currencies in the sales-integrity report");
    problem.setDetail(ex.getMessage());
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
