package id.co.nativeapp.restaurant.inventory.domain;

import java.util.List;
import java.util.UUID;

/**
 * Deactivating an ingredient was blocked because at least one menu-item recipe still references it
 * (ADR 0050 phase A) — a silent deactivation would leave HPP holes and dead depletion rows. Mapped
 * to {@code 409} ({@code ingredient-in-recipe}); the detail names up to ten referencing items so
 * the operator knows which recipes to edit first.
 */
public class IngredientInUseException extends RuntimeException {

  public IngredientInUseException(UUID ingredientId, List<String> referencingItemNames) {
    super(
        "Ingredient "
            + ingredientId
            + " is used by menu-item recipes: "
            + String.join(", ", referencingItemNames)
            + " — edit those recipes first.");
  }
}
