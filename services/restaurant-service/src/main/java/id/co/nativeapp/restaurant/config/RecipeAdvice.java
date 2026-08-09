package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.inventory.domain.IngredientInUseException;
import id.co.nativeapp.restaurant.recipe.domain.RecipeValidationException;
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
 * Maps the recipe-feature faults (ADR 0050 phase A) to RFC-7807 {@link ProblemDetail} responses, in
 * {@code IngredientAdvice}'s narrow feature-scoped style.
 *
 * <ul>
 *   <li>{@link RecipeValidationException} — well-formed but unprocessable recipe content → {@code
 *       422} ({@code recipe-validation})
 *   <li>{@link IngredientInUseException} — deactivation vetoed while recipes reference the
 *       ingredient → {@code 409} ({@code ingredient-in-recipe})
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecipeAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(RecipeValidationException.class)
  public ProblemDetail handleRecipeValidation(
      RecipeValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "recipe-validation", request);
    problem.setTitle("Recipe validation failed");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(IngredientInUseException.class)
  public ProblemDetail handleIngredientInUse(
      IngredientInUseException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "ingredient-in-recipe", request);
    problem.setTitle("Ingredient is used by a recipe");
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
