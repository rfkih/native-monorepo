package id.co.nativeapp.finance.ap.controller;

import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.domain.VendorNotFoundException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the AP controllers — the fault shapes the shared {@code
 * libs/security ApiExceptionHandler} does not own. Scoped to the {@code ap.controller} package so
 * its {@code IllegalArgumentException → 400} mapping does not affect other controllers.
 *
 * <ul>
 *   <li>{@link VendorNotFoundException} / {@link BillNotFoundException} → {@code 404}, with a
 *       generic detail (reads are RLS-scoped, so a foreign-tenant id is indistinguishable from a
 *       missing one — no existence disclosure).
 *   <li>{@link BillStateException} → {@code 409} (an illegal lifecycle transition: double-post,
 *       overpay, void-when-paid). The detail carries the state message (non-sensitive).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad input the bean-validation layer did not
 *       already reject, e.g. an unknown ISO-4217 currency code).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.ap.controller")
public class ApAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown vendor / bill in the bound tenant → 404 (generic detail; no existence leak). */
  @ExceptionHandler({VendorNotFoundException.class, BillNotFoundException.class})
  public ProblemDetail handleNotFound(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "ap-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such vendor or bill is accessible.");
    return decorate(problem, request);
  }

  /** An illegal bill lifecycle transition → 409. */
  @ExceptionHandler(BillStateException.class)
  public ProblemDetail handleInvalidState(BillStateException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "bill-invalid-state"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Bad input not caught by bean validation (e.g. an unknown currency code) → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "ap-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /**
   * A divergent-currency bill post (mirroring AR's code-review M1) → 422 — the bill currency does
   * not match the company's base currency established for the period.
   */
  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "bill-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail("The bill currency must match the company's base currency for the period.");
    return decorate(problem, request);
  }

  /**
   * A concurrent conflict (a raced double-post on the same draft, or a raced identical payment
   * hitting the idempotency-key unique index) → 409 rather than a leaked 500. No money is
   * double-posted — the losing transaction rolls back; the client may retry.
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleConcurrentConflict(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "ap-concurrent-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail("The operation conflicted with a concurrent change; please retry.");
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
