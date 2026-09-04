package id.co.nativeapp.finance.inventory.controller;

import id.co.nativeapp.finance.inventory.domain.InventoryMethodAlreadyActiveException;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodSealedPeriodException;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 {@link ProblemDetail} advice for the inventory-method controller (ADR 0067 §5) — scoped
 * to this package so its {@code IllegalArgumentException -> 400} mapping does not affect other
 * controllers (the {@code OpeningBalanceAdvice} pattern).
 *
 * <ul>
 *   <li>{@link InventoryMethodAlreadyActiveException} -&gt; {@code 409} (once-only; a different
 *       key/payload).
 *   <li>{@link InventoryMethodSealedPeriodException} -&gt; {@code 422} (the cutover period is
 *       tax-sealed).
 *   <li>{@link MismatchedPostingCurrencyException} -&gt; {@code 422} (divergent posting currency).
 *   <li>{@link IllegalArgumentException} -&gt; {@code 400} (bad input incl. keyless / malformed).
 * </ul>
 */
@RestControllerAdvice(basePackages = "id.co.nativeapp.finance.inventory.controller")
public class InventoryMethodAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(InventoryMethodAlreadyActiveException.class)
  public ProblemDetail handleAlreadyActive(
      InventoryMethodAlreadyActiveException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "inventory-method-already-active"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(InventoryMethodSealedPeriodException.class)
  public ProblemDetail handleSealedPeriod(
      InventoryMethodSealedPeriodException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "inventory-method-sealed-period"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(MismatchedPostingCurrencyException.class)
  public ProblemDetail handleCurrencyMismatch(
      MismatchedPostingCurrencyException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "inventory-method-currency-mismatch"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "inventory-method-invalid-request"));
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
