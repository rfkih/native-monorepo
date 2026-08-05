package id.co.nativeapp.finance.assets.controller;

import id.co.nativeapp.finance.assets.domain.AssetAlreadyDisposedException;
import id.co.nativeapp.finance.assets.domain.AssetDepreciationBehindException;
import id.co.nativeapp.finance.assets.domain.AssetNotFoundException;
import id.co.nativeapp.finance.assets.domain.AssetSealedPeriodException;
import id.co.nativeapp.finance.assets.domain.IdempotencyKeyConflictException;
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
 * RFC 7807 {@link ProblemDetail} advice for the assets/deferrals controllers (Phase 6) — the fault
 * shapes the shared {@code libs/security ApiExceptionHandler} does not own. Scoped to the {@code
 * assets.controller} package (mirrors {@code ApAdvice} / {@code TaxAdvice} / {@code BudgetAdvice}).
 *
 * <ul>
 *   <li>{@link AssetNotFoundException} → {@code 404} (generic detail — RLS-scoped reads, no
 *       existence disclosure).
 *   <li>{@link IllegalArgumentException} → {@code 400} (bad bounds: salvage ≥ cost, bad currency,
 *       blank name — the domain-factory guards bean validation does not cover).
 *   <li>{@link DataIntegrityViolationException} → {@code 409} (a raced duplicate run hitting {@code
 *       uq_amortization_run} behind the advisory lock).
 *   <li>{@link MismatchedPostingCurrencyException} → {@code 422} (a posting currency diverging from
 *       the period's GL — the whole transaction rolls back, no partial posting).
 *   <li>{@link AssetSealedPeriodException} → {@code 422} (the opening period is tax-sealed —
 *       brought-forward registration only, mirrors {@code OpeningBalanceSealedPeriodException}).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.assets.controller")
public class AssetAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Unknown asset in the bound tenant → 404 (generic detail; no existence leak). */
  @ExceptionHandler(AssetNotFoundException.class)
  public ProblemDetail handleNotFound(AssetNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "asset-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such asset is accessible.");
    return decorate(problem, request);
  }

  /**
   * Bad input not caught by bean validation (salvage ≥ cost, bad ISO currency, blank name) → 400.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "asset-invalid-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A second disposal attempt under a different Idempotency-Key → 409 (ADR 0022). */
  @ExceptionHandler(AssetAlreadyDisposedException.class)
  public ProblemDetail handleAlreadyDisposed(
      AssetAlreadyDisposedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "asset-already-disposed"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** Depreciation out of step with the disposal's posting period → 409 (ADR 0022). */
  @ExceptionHandler(AssetDepreciationBehindException.class)
  public ProblemDetail handleDepreciationBehind(
      AssetDepreciationBehindException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "asset-depreciation-behind"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** An Idempotency-Key reused against a DIFFERENT asset → 409 (path id is authoritative). */
  @ExceptionHandler(IdempotencyKeyConflictException.class)
  public ProblemDetail handleKeyConflict(
      IdempotencyKeyConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "asset-idempotency-key-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A raced duplicate (uq_amortization_run behind the advisory lock) → 409, retryable. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleConflict(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "asset-concurrent-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail("The operation conflicted with a concurrent change; please retry.");
    return decorate(problem, request);
  }

  /** A posting currency diverging from the period's GL → 422 (no FX; single-base-currency). */
  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "asset-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(
        "The posting currency must match the company's base currency for the period.");
    return decorate(problem, request);
  }

  /** A brought-forward registration's opening period is tax-sealed → 422 (ADR 0037). */
  @ExceptionHandler(AssetSealedPeriodException.class)
  public ProblemDetail handleSealedPeriod(
      AssetSealedPeriodException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "asset-sealed-period"));
    problem.setTitle("Unprocessable Entity");
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
