package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.selforder.domain.SelfOrderCapExceededException;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccessForbiddenException;
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
 * The Phase 6 (ADR 0029) self-order-feature-specific RFC 7807 {@link ProblemDetail} advice —
 * narrow, feature-scoped handlers ordered ahead of the shared {@code libs/security
 * ApiExceptionHandler} catch-all (mirrors {@code OrderAdvice}/{@code PromotionAdvice}'s style). The
 * garbage/malformed/unverifiable-token {@code 401} case is handled directly by {@link
 * SelfOrderTokenFilter} (a filter runs before any {@code @RestControllerAdvice} could see it).
 *
 * <ul>
 *   <li>{@link SelfOrderAccessForbiddenException} — a non-owner/manager caller attempted a
 *       self-order-access management write ({@code GET}/{@code POST /rotate}) -> {@code 403 Forbidden}
 *       ({@code self-order-access-forbidden}).
 *   <li>{@link SelfOrderCapExceededException} — an outlet's unconfirmed self-order count is at/over
 *       the cap -> {@code 429 Too Many Requests} ({@code self-order-cap-exceeded}).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SelfOrderAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(SelfOrderAccessForbiddenException.class)
  public ProblemDetail handleAccessForbidden(
      SelfOrderAccessForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "self-order-access-forbidden", request);
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(SelfOrderCapExceededException.class)
  public ProblemDetail handleCapExceeded(
      SelfOrderCapExceededException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "self-order-cap-exceeded", request);
    problem.setTitle("Too many unconfirmed self-order rows");
    problem.setDetail(ex.getMessage());
    problem.setProperty("businessId", ex.getBusinessId().toString());
    problem.setProperty("unconfirmedCount", ex.getUnconfirmedCount());
    problem.setProperty("cap", ex.getCap());
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
