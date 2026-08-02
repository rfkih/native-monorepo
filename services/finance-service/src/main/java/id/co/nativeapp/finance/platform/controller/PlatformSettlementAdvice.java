package id.co.nativeapp.finance.platform.controller;

import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the platform-settlements controller (ADR 0036 Phase C)
 * — scoped to this package so its {@code IllegalArgumentException → 400} mapping does not affect
 * other controllers (the PayrollLiabilityAdvice pattern).
 *
 * <ul>
 *   <li>{@link PlatformSettlementIdempotencyKeyConflictException} → {@code 409} (replayed key,
 *       different payload).
 *   <li>{@link PlatformNetExceedsGrossException} → {@code 422} (negative commission — platform
 *       subsidies deferred in v1).
 *   <li>{@link PlatformOverSettlementException} → {@code 422} (the channel's outstanding does not
 *       cover the requested gross).
 *   <li>{@link MismatchedPostingCurrencyException} → {@code 422} (divergent posting currency).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad input incl. the keyless-settle guard).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.platform.controller")
public class PlatformSettlementAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(PlatformSettlementIdempotencyKeyConflictException.class)
  public ProblemDetail handleKeyConflict(
      PlatformSettlementIdempotencyKeyConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "platform-settlement-idempotency-key-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(PlatformNetExceedsGrossException.class)
  public ProblemDetail handleNetExceedsGross(
      PlatformNetExceedsGrossException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "platform-settlement-net-exceeds-gross"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(PlatformOverSettlementException.class)
  public ProblemDetail handleOverSettlement(
      PlatformOverSettlementException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "platform-settlement-over-settlement"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "platform-settlement-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "platform-settlement-invalid-request"));
    problem.setTitle("Bad Request");
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
