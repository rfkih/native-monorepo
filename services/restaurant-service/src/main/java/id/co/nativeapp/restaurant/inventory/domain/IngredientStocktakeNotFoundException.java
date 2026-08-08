package id.co.nativeapp.restaurant.inventory.domain;

import java.util.UUID;

/**
 * The referenced ingredient stocktake does not exist under the bound tenant (RLS makes a
 * cross-tenant id indistinguishable from a missing one — deliberately). Mapped to {@code 404 Not
 * Found} ({@code ingredient-stocktake-not-found}) by {@code IngredientAdvice}.
 */
public class IngredientStocktakeNotFoundException extends RuntimeException {

  public IngredientStocktakeNotFoundException(UUID ingredientStocktakeId) {
    super("ingredient stocktake not found: " + ingredientStocktakeId);
  }
}
