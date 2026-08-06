package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.stocktake.domain.StocktakeCurrencyMismatchException;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeNotFoundException;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeStockRacedException;
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
 * Maps the stocktake faults (ADR 0038 phase 3, daily close v2) to RFC-7807 {@link ProblemDetail}
 * responses. Mirrors {@code RegisterAdvice}/{@code StockExceptionHandler}'s narrow, feature-scoped
 * style.
 *
 * <ul>
 *   <li>{@link StocktakeCurrencyMismatchException} — lines priced in different currencies → {@code
 *       422} ({@code stocktake-currency-mismatch})
 *   <li>{@link StocktakeNotFoundException} — unknown/cross-tenant id → {@code 404} ({@code
 *       stocktake-not-found})
 *   <li>{@link StocktakeStockRacedException} — a concurrent sale bumped a counted item's version →
 *       {@code 409} ({@code stocktake-stock-raced}, {@code retryable=true})
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StocktakeAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(StocktakeCurrencyMismatchException.class)
  public ProblemDetail handleCurrencyMismatch(
      StocktakeCurrencyMismatchException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "stocktake-currency-mismatch", request);
    problem.setTitle("Mismatched currency across stocktake lines");
    problem.setDetail(ex.getMessage());
    problem.setProperty("expectedCurrency", ex.getExpectedCurrency());
    problem.setProperty("actualCurrency", ex.getActualCurrency());
    return problem;
  }

  @ExceptionHandler(StocktakeNotFoundException.class)
  public ProblemDetail handleNotFound(StocktakeNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "stocktake-not-found", request);
    problem.setTitle("Stocktake not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(StocktakeStockRacedException.class)
  public ProblemDetail handleStockRaced(
      StocktakeStockRacedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "stocktake-stock-raced", request);
    problem.setTitle("Stock changed during the count");
    problem.setDetail(ex.getMessage());
    problem.setProperty("retryable", true);
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
