package id.co.nativeapp.finance.opening.controller;

import id.co.nativeapp.finance.opening.domain.OpeningBalanceAlreadyRecordedException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceIdempotencyKeyConflictException;
import id.co.nativeapp.finance.opening.domain.OpeningBalancePnlAccountException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceSealedPeriodException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the opening-balances controller (ADR 0037) — scoped to
 * this package so its {@code IllegalArgumentException → 400} mapping does not affect other
 * controllers (the PlatformSettlementAdvice pattern).
 *
 * <ul>
 *   <li>{@link OpeningBalanceIdempotencyKeyConflictException} → {@code 409} (replayed key,
 *       different payload).
 *   <li>{@link OpeningBalanceAlreadyRecordedException} → {@code 409} (once-only; a different key).
 *   <li>{@link OpeningBalancePnlAccountException} → {@code 422} (a REVENUE/EXPENSE line).
 *   <li>{@link OpeningBalanceSealedPeriodException} → {@code 422} (the period is tax-sealed).
 *   <li>{@link MismatchedPostingCurrencyException} → {@code 422} (divergent posting currency).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad input incl. keyless / unknown account).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.opening.controller")
public class OpeningBalanceAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(OpeningBalanceIdempotencyKeyConflictException.class)
  public ProblemDetail handleKeyConflict(
      OpeningBalanceIdempotencyKeyConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "opening-balance-idempotency-key-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(OpeningBalanceAlreadyRecordedException.class)
  public ProblemDetail handleAlreadyRecorded(
      OpeningBalanceAlreadyRecordedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "opening-balances-already-recorded"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(OpeningBalancePnlAccountException.class)
  public ProblemDetail handlePnlAccount(
      OpeningBalancePnlAccountException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "opening-balance-pnl-account"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(OpeningBalanceSealedPeriodException.class)
  public ProblemDetail handleSealedPeriod(
      OpeningBalanceSealedPeriodException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "opening-balance-sealed-period"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "opening-balance-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "opening-balance-invalid-request"));
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
