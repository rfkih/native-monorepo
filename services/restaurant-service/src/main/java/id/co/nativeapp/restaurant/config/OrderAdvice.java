package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.order.domain.OfflineReplayValidationException;
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
 * The order-feature-specific RFC 7807 {@link ProblemDetail} advice — narrow, feature-scoped
 * handlers ordered ahead of the shared {@code libs/security ApiExceptionHandler} catch-all (mirrors
 * {@code PromotionAdvice}/{@code StockExceptionHandler}'s style).
 *
 * <ul>
 *   <li>{@link OfflineReplayValidationException} — a checkout request violates the Phase 5 (ADR
 *       0028) offline-replay contract: {@code clientOccurredAt} without {@code offlineReplay},
 *       {@code clientOccurredAt} outside the &plusmn;48h/5min bound, or an offline-forbidden field
 *       (non-CASH tender, coupon, points redemption, gift card) → {@code 422 Unprocessable Entity}
 *       ({@code offline-replay-invalid}).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(OfflineReplayValidationException.class)
  public ProblemDetail handleOfflineReplayInvalid(
      OfflineReplayValidationException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "offline-replay-invalid", request);
    problem.setTitle("Invalid offline-replay checkout");
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
