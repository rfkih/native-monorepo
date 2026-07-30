package id.co.nativeapp.finance.tax.controller;

import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.tax.domain.NoVatActivityException;
import id.co.nativeapp.finance.tax.domain.TaxFilingNotFoundException;
import id.co.nativeapp.finance.tax.domain.TaxFilingStateException;
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
 * RFC 7807 {@link ProblemDetail} advice for the tax controllers (Phase 4 Tax / PPN) — the fault
 * shapes the shared {@code libs/security ApiExceptionHandler} does not own. Scoped to the {@code
 * tax.controller} package so its {@code IllegalArgumentException → 400} mapping does not affect
 * other controllers (mirrors {@code ApAdvice}).
 *
 * <ul>
 *   <li>{@link TaxFilingNotFoundException} → {@code 404} (generic detail — reads are RLS-scoped, so
 *       a foreign-tenant id is indistinguishable from a missing one; no existence disclosure).
 *   <li>{@link TaxFilingStateException} → {@code 409} (an illegal lifecycle transition: re-settle,
 *       or settling a creditable / zero-net return).
 *   <li>{@link NoVatActivityException} → {@code 400} (nothing to file — no VAT, or a net-void
 *       period).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad input not caught by bean validation).
 *   <li>{@link MismatchedPostingCurrencyException} / {@link GlMultiCurrencyException} → {@code 422}
 *       (the period's GL is multi-currency, or a filing/settlement currency diverges).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.tax.controller")
public class TaxAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown filing in the bound tenant → 404 (generic detail; no existence leak). */
  @ExceptionHandler(TaxFilingNotFoundException.class)
  public ProblemDetail handleNotFound(TaxFilingNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "tax-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such tax filing is accessible.");
    return decorate(problem, request);
  }

  /** An illegal filing lifecycle transition → 409. */
  @ExceptionHandler(TaxFilingStateException.class)
  public ProblemDetail handleInvalidState(TaxFilingStateException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "tax-invalid-state"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Nothing to file for the period (no VAT activity, or a net-void period) → 400. */
  @ExceptionHandler(NoVatActivityException.class)
  public ProblemDetail handleNoActivity(NoVatActivityException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "tax-no-activity"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Bad input not caught by bean validation → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "tax-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A divergent / multi-currency period → 422 (no FX; single-base-currency invariant). */
  @ExceptionHandler({MismatchedPostingCurrencyException.class, GlMultiCurrencyException.class})
  public ProblemDetail handleCurrencyMismatch(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "tax-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail("The period's VAT must be in a single base currency.");
    return decorate(problem, request);
  }

  /**
   * A concurrent conflict (a raced double-file hitting the {@code uq_tax_filing} unique index, or a
   * raced settle) → 409 rather than a leaked 500. No money is double-posted — the losing
   * transaction rolls back; the client may retry.
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleConcurrentConflict(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "tax-concurrent-conflict"));
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
