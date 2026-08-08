package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.inventory.domain.IngredientNameConflictException;
import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktakeNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the ingredient-inventory not-found faults (ADR 0046 phase 1) to RFC-7807 {@link
 * ProblemDetail} responses. Mirrors {@code StocktakeAdvice}'s narrow, feature-scoped style; the
 * currency-mismatch and stock-raced faults are reused from the stocktake feature verbatim and are
 * already mapped by {@code StocktakeAdvice} (rules 7/12 — no duplicate mapping needed).
 *
 * <ul>
 *   <li>{@link IngredientNotFoundException} — unknown/cross-tenant id → {@code 404} ({@code
 *       ingredient-not-found})
 *   <li>{@link IngredientStocktakeNotFoundException} — unknown/cross-tenant id → {@code 404}
 *       ({@code ingredient-stocktake-not-found})
 *   <li>{@link IngredientNameConflictException} — duplicate ACTIVE name at the outlet → {@code 409}
 *       ({@code ingredient-name-conflict})
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngredientAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(IngredientNotFoundException.class)
  public ProblemDetail handleIngredientNotFound(
      IngredientNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "ingredient-not-found", request);
    problem.setTitle("Ingredient not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(IngredientStocktakeNotFoundException.class)
  public ProblemDetail handleIngredientStocktakeNotFound(
      IngredientStocktakeNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.NOT_FOUND, "ingredient-stocktake-not-found", request);
    problem.setTitle("Ingredient stocktake not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(IngredientNameConflictException.class)
  public ProblemDetail handleIngredientNameConflict(
      IngredientNameConflictException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "ingredient-name-conflict", request);
    problem.setTitle("Ingredient name already in use");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
