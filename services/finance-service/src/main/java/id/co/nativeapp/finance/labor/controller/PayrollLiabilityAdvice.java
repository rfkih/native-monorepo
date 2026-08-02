package id.co.nativeapp.finance.labor.controller;

import id.co.nativeapp.finance.labor.domain.NegativeLiabilityBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityBucketEmptyException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityNotSettleableException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilitySuspenseBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedgerNotFoundException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementAlreadySettledException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementIdempotencyKeyConflictException;
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
 * RFC 7807 {@link ProblemDetail} advice for the payroll-liabilities controller (ADR 0032, Track P
 * phase P5) — the fault shapes the shared {@code libs/security ApiExceptionHandler} does not own.
 * Scoped to the {@code labor.controller} package so its {@code IllegalArgumentException -> 400}
 * mapping does not affect other controllers (mirrors {@code TaxAdvice}/{@code AssetAdvice}).
 *
 * <ul>
 *   <li>{@link PayrollRunLedgerNotFoundException} -> {@code 404} (generic detail; RLS-scoped reads
 *       make a foreign-tenant id indistinguishable from a missing one — no existence disclosure).
 *   <li>{@link PayrollLiabilityNotSettleableException} / {@link
 *       PayrollSettlementAlreadySettledException} -> {@code 409} (an illegal settlement state: not
 *       yet recognised, superseded, or already settled under a different key).
 *   <li>{@link PayrollSettlementIdempotencyKeyConflictException} -> {@code 409} (the key was
 *       already used for a different {@code (run, kind)} — path/body authoritative).
 *   <li>{@link PayrollLiabilityBucketEmptyException} -> {@code 409} (the run never recognised this
 *       bucket — nothing to settle).
 *   <li>{@link PayrollLiabilitySuspenseBucketException} -> {@code 409} (the bucket posted to
 *       SUSPENSE, unmapped at accrual time — must be reclassified before it can be settled).
 *   <li>{@link IllegalArgumentException} -> {@code 400} (bad input not caught by bean validation,
 *       incl. the keyless-settle guard).
 *   <li>{@link NegativeLiabilityBucketException} / {@link MismatchedPostingCurrencyException} ->
 *       {@code 422} (a negative bucket is not settleable standalone; a divergent posting currency).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.labor.controller")
public class PayrollLiabilityAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown run ledger in the bound tenant -> 404 (generic detail; no existence leak). */
  @ExceptionHandler(PayrollRunLedgerNotFoundException.class)
  public ProblemDetail handleNotFound(
      PayrollRunLedgerNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such payroll run ledger is accessible.");
    return decorate(problem, request);
  }

  /** The run's liability is not in a settleable state (never posted, or superseded) -> 409. */
  @ExceptionHandler(PayrollLiabilityNotSettleableException.class)
  public ProblemDetail handleNotSettleable(
      PayrollLiabilityNotSettleableException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-not-settleable"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** The bucket is already settled under a different Idempotency-Key -> 409. */
  @ExceptionHandler(PayrollSettlementAlreadySettledException.class)
  public ProblemDetail handleAlreadySettled(
      PayrollSettlementAlreadySettledException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-settlement-already-settled"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** The Idempotency-Key was reused against a DIFFERENT (run, kind) -> 409. */
  @ExceptionHandler(PayrollSettlementIdempotencyKeyConflictException.class)
  public ProblemDetail handleKeyConflict(
      PayrollSettlementIdempotencyKeyConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-settlement-idempotency-key-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** The run never recognised this bucket — nothing to settle -> 409. */
  @ExceptionHandler(PayrollLiabilityBucketEmptyException.class)
  public ProblemDetail handleBucketEmpty(
      PayrollLiabilityBucketEmptyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-bucket-empty"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A suspense-parked bucket must be reclassified before it can be settled -> 409. */
  @ExceptionHandler(PayrollLiabilitySuspenseBucketException.class)
  public ProblemDetail handleSuspenseBucket(
      PayrollLiabilitySuspenseBucketException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-suspense-bucket"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Bad input not caught by bean validation (incl. the keyless-settle guard) -> 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A negative bucket cannot be settled standalone (v1 residual, ADR 0032) -> 422. */
  @ExceptionHandler(NegativeLiabilityBucketException.class)
  public ProblemDetail handleNegativeBucket(
      NegativeLiabilityBucketException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-negative-bucket"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A divergent posting currency (no FX; single-base-currency invariant) -> 422. */
  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(
        "The settlement currency must match the company's base currency for the period.");
    return decorate(problem, request);
  }

  /**
   * A concurrent conflict (a raced double-settle hitting {@code uq_payroll_settlement_once}, or a
   * raced identical retry hitting {@code uq_payroll_settlement_idempotency_key}) -> 409 rather than
   * a leaked 500. No money is double-posted — the losing transaction rolls back; the client may
   * retry.
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleConcurrentConflict(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payroll-liability-concurrent-conflict"));
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
