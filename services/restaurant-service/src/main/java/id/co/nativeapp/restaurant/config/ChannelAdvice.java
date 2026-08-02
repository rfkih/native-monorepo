package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.channel.domain.SalesChannelCodeConflictException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelForbiddenException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelNotFoundException;
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
 * Maps the sales-channel faults (ADR 0036 Phase B2) to RFC-7807 {@link ProblemDetail} responses.
 * Mirrors {@code RegisterAdvice}/{@code PromotionAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link SalesChannelNotFoundException} — unknown/cross-tenant id → {@code 404} ({@code
 *       sales-channel-not-found})
 *   <li>{@link SalesChannelCodeConflictException} — duplicate {@code code} for the tenant → {@code
 *       409} ({@code sales-channel-code-conflict})
 *   <li>{@link SalesChannelForbiddenException} — a non-owner/manager caller attempted a
 *       create/update → {@code 403} ({@code sales-channel-forbidden})
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChannelAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(SalesChannelNotFoundException.class)
  public ProblemDetail handleNotFound(
      SalesChannelNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "sales-channel-not-found", request);
    problem.setTitle("Sales channel not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(SalesChannelCodeConflictException.class)
  public ProblemDetail handleCodeConflict(
      SalesChannelCodeConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "sales-channel-code-conflict", request);
    problem.setTitle("Sales channel code conflict");
    problem.setDetail(ex.getMessage());
    problem.setProperty("code", ex.getCode());
    return problem;
  }

  @ExceptionHandler(SalesChannelForbiddenException.class)
  public ProblemDetail handleForbidden(
      SalesChannelForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "sales-channel-forbidden", request);
    problem.setTitle("Forbidden");
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
