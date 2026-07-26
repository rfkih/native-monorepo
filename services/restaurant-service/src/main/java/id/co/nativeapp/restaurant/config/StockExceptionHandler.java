package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.domain.UntrackedStockException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.NoSuchElementException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps stock-domain exceptions to {@code 422 Unprocessable Entity} RFC-7807 ProblemDetail
 * responses.
 *
 * <p>Ordered before the shared {@link id.co.nativeapp.security.ApiExceptionHandler} (which owns
 * the catch-all {@link Exception} → 500 mapping) so these narrower handlers win.
 *
 * <ul>
 *   <li>{@link InsufficientStockException} — checkout attempted to sell more than is in stock.
 *       Detail carries the item id and name (no PII — these are catalog attributes, not user data).
 *   <li>{@link UntrackedStockException} — {@code POST .../stock/add} called on an item that has no
 *       tracked stock yet. Guides the caller to {@code PUT .../stock} first.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StockExceptionHandler {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /**
   * {@link InsufficientStockException} → {@code 422 Unprocessable Entity}.
   *
   * <p>The detail includes the item name and id (catalog data, not PII) and the requested vs.
   * available counts so the UI can display a meaningful message to the cashier. The sale is
   * rolled back by the throwing transaction.
   */
  @ExceptionHandler(InsufficientStockException.class)
  public ProblemDetail handleInsufficientStock(
      InsufficientStockException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock", request);
    problem.setTitle("Insufficient stock");
    problem.setDetail(ex.getMessage());
    problem.setProperty("menuItemId", ex.getMenuItemId().toString());
    problem.setProperty("itemName", ex.getItemName());
    problem.setProperty("requested", ex.getRequested());
    problem.setProperty("available", ex.getAvailable());
    return problem;
  }

  /**
   * {@link UntrackedStockException} → {@code 422 Unprocessable Entity}.
   *
   * <p>Guides the API caller to set an absolute stock quantity first before using the delta
   * endpoint.
   */
  @ExceptionHandler(UntrackedStockException.class)
  public ProblemDetail handleUntrackedStock(
      UntrackedStockException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "untracked-stock", request);
    problem.setTitle("Untracked stock");
    problem.setDetail(ex.getMessage());
    problem.setProperty("menuItemId", ex.getMenuItemId().toString());
    return problem;
  }

  /**
   * {@link NoSuchElementException} → {@code 404 Not Found}.
   *
   * <p>Thrown by service/writer methods (e.g. menu item delete, 86/un-86) when the requested
   * resource does not exist or is not visible to the current tenant (RLS). The exception message is
   * included as {@code detail} — it never contains PII (only catalog identifiers).
   */
  @ExceptionHandler(NoSuchElementException.class)
  public ProblemDetail handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "not-found", request);
    problem.setTitle("Not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  private static ProblemDetail problem(
      HttpStatus status, String slug, HttpServletRequest request) {
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
