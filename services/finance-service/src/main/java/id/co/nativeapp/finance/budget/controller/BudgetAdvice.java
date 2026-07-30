package id.co.nativeapp.finance.budget.controller;

import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.domain.UnknownAccountException;
import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the budget controllers (Phase 5) — the fault shapes the
 * shared {@code libs/security ApiExceptionHandler} does not own. Scoped to the {@code
 * budget.controller} package so its {@code IllegalArgumentException → 400} mapping does not affect
 * other controllers (mirrors {@code ApAdvice} / {@code TaxAdvice}).
 *
 * <ul>
 *   <li>{@link BudgetNotFoundException} → {@code 404} (generic detail — reads are RLS-scoped; no
 *       existence disclosure).
 *   <li>{@link UnknownAccountException} → {@code 400} (a line targets a non-existent account).
 *   <li>{@link IllegalArgumentException} → {@code 400} (blank name, bad currency, duplicate
 *       account).
 *   <li>{@link DataIntegrityViolationException} → {@code 409} (a duplicate budget name+period).
 *   <li>{@link GlMultiCurrencyException} → {@code 422} (the period's GL is multi-currency — from
 *       the variance report's trial-balance read).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.budget.controller")
public class BudgetAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown budget in the bound tenant → 404 (generic detail; no existence leak). */
  @ExceptionHandler(BudgetNotFoundException.class)
  public ProblemDetail handleNotFound(BudgetNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "budget-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such budget is accessible.");
    return decorate(problem, request);
  }

  /** A line targets an account not in the chart of accounts → 400 (names the account). */
  @ExceptionHandler(UnknownAccountException.class)
  public ProblemDetail handleUnknownAccount(
      UnknownAccountException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "budget-unknown-account"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /**
   * Bad input not caught by bean validation (blank name, bad currency, duplicate account) → 400.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "budget-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A duplicate budget name+period for the tenant (uq_budget_company_name_period) → 409. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleConflict(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "budget-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail("A budget with this name already exists for the period.");
    return decorate(problem, request);
  }

  /** The variance period's GL spans more than one currency → 422. */
  @ExceptionHandler(GlMultiCurrencyException.class)
  public ProblemDetail handleMultiCurrency(
      GlMultiCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "budget-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail("The period's ledger spans more than one currency.");
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
