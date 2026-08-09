package id.co.nativeapp.restaurant.recipe.domain;

/**
 * A recipe write violated a domain rule (ADR 0050): unknown/cross-outlet ingredient, a modifier
 * option that does not belong to the menu item, or a qty sign violation. Mapped to HTTP 422 by the
 * controller advice (the request was well-formed; the content is unprocessable).
 */
public class RecipeValidationException extends RuntimeException {

  public RecipeValidationException(String message) {
    super(message);
  }
}
