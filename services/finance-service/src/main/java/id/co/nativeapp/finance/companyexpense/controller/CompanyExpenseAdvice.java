package id.co.nativeapp.finance.companyexpense.controller;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseIdempotencyConflictException;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseNotFoundException;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseSealedPeriodException;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseStateException;
import id.co.nativeapp.finance.companyexpense.domain.InvalidCompanyExpenseException;
import id.co.nativeapp.finance.companyexpense.domain.InvalidGlHintException;
import id.co.nativeapp.finance.companyexpense.domain.UnknownBusinessUnitException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the company-expense controller (the {@code ApAdvice}
 * shape). Scoped to this package so the {@code IllegalArgumentException → 400} mapping does not
 * leak into other controllers.
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.companyexpense.controller")
public class CompanyExpenseAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown expense in the bound tenant → 404 (generic detail; no existence leak). */
  @ExceptionHandler(CompanyExpenseNotFoundException.class)
  public ProblemDetail handleNotFound(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "company-expense-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such company expense is accessible.");
    return decorate(problem, request);
  }

  /** An illegal lifecycle transition (double-void) or a reused key with a new payload → 409. */
  @ExceptionHandler({
    CompanyExpenseStateException.class,
    CompanyExpenseIdempotencyConflictException.class
  })
  public ProblemDetail handleConflict(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "company-expense-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Input the domain rejects (kind shape, hint, outlet, sealed period, currency) → 422. */
  @ExceptionHandler({
    InvalidCompanyExpenseException.class,
    InvalidGlHintException.class,
    UnknownBusinessUnitException.class,
    CompanyExpenseSealedPeriodException.class,
    MismatchedPostingCurrencyException.class
  })
  public ProblemDetail handleUnprocessable(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "company-expense-invalid"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /**
   * A database-level collision the service could not recover (an unkeyed duplicate racing a
   * constraint, an optimistic-lock loss) → 409, matching {@code ApAdvice} (review m4).
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    org.springframework.orm.ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleDataConflict(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "company-expense-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail("The request conflicted with a concurrent change; retry the operation.");
    return decorate(problem, request);
  }

  /** Bad input not caught upstream (unknown currency code, oversized key) → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "company-expense-bad-request"));
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
