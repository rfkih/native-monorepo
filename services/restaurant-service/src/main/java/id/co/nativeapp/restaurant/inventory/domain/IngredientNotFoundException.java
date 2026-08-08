package id.co.nativeapp.restaurant.inventory.domain;

import java.util.UUID;

/**
 * The referenced ingredient does not exist under the bound tenant (RLS makes a cross-tenant id
 * indistinguishable from a missing one — deliberately). Mapped to {@code 404 Not Found} ({@code
 * ingredient-not-found}) by {@code IngredientAdvice}.
 */
public class IngredientNotFoundException extends RuntimeException {

  public IngredientNotFoundException(UUID ingredientId) {
    super("ingredient not found: " + ingredientId);
  }
}
