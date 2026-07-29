package id.co.nativeapp.finance.bank.controller;

import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.domain.ReconciliationStateException;
import id.co.nativeapp.finance.bank.domain.StatementLineNotFoundException;
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
 * RFC 7807 {@link ProblemDetail} advice for the bank controllers — the fault shapes the shared
 * {@code libs/security ApiExceptionHandler} does not own. Scoped to the {@code bank.controller}
 * package so its {@code IllegalArgumentException → 400} mapping does not affect other controllers.
 * Mirrors {@code ApAdvice}.
 *
 * <ul>
 *   <li>{@link BankAccountNotFoundException} / {@link StatementLineNotFoundException} → {@code
 *       404}, with a generic detail (reads are RLS-scoped, so a foreign-tenant id is
 *       indistinguishable from a missing one — no existence disclosure).
 *   <li>{@link ReconciliationStateException} → {@code 409} (a statement line that is already
 *       RECONCILED — no double-reconcile). The detail carries the state message (non-sensitive).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad input the bean-validation layer did not
 *       already reject, e.g. a category/direction mismatch such as {@code BANK_FEE} on a deposit).
 *   <li>{@link MismatchedPostingCurrencyException} → {@code 422} (the statement line currency
 *       diverges from the company's established period currency).
 *   <li>{@link DataIntegrityViolationException} / {@link ObjectOptimisticLockingFailureException} →
 *       {@code 409} (a raced concurrent conflict, e.g. two simultaneous reconciles of the same line
 *       racing the {@code source_event_id} UNIQUE constraint).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.bank.controller")
public class BankAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /**
   * Unknown bank account / statement line in the bound tenant → 404 (generic detail; no existence
   * leak).
   */
  @ExceptionHandler({BankAccountNotFoundException.class, StatementLineNotFoundException.class})
  public ProblemDetail handleNotFound(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "bank-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such bank account or statement line is accessible.");
    return decorate(problem, request);
  }

  /** A double-reconcile attempt on an already-RECONCILED line → 409. */
  @ExceptionHandler(ReconciliationStateException.class)
  public ProblemDetail handleInvalidState(
      ReconciliationStateException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "bank-invalid-state"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Bad input not caught by bean validation (e.g. a category/direction mismatch) → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "bank-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /**
   * A divergent-currency reconciliation post (mirroring AR/AP's currency guard) → 422 — the
   * statement line currency does not match the currency already established for the period.
   */
  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "bank-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(
        "The statement line currency must match the company's base currency for the period.");
    return decorate(problem, request);
  }

  /**
   * A concurrent conflict (a raced double-reconcile hitting the {@code source_event_id} unique
   * index) → 409 rather than a leaked 500. No money is double-posted — the losing transaction rolls
   * back; the client may retry.
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleConcurrentConflict(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "bank-concurrent-conflict"));
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
