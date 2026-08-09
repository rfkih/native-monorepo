package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.inventory.domain.IngredientInUseException;
import id.co.nativeapp.restaurant.inventory.service.IngredientDeactivationGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The recipe feature's {@link IngredientDeactivationGuard} (ADR 0050 phase A): vetoes deactivating
 * an ingredient that any menu-item recipe still references — a silent deactivation would leave HPP
 * holes and dead depletion rows. The 409 detail names up to ten referencing items so the operator
 * knows which recipes to edit first.
 *
 * <p>Delegates the reads to {@link RecipeReader} (repositories are a service-layer concern — the
 * ArchUnit {@code repositoriesAreAccessedOnlyByTheServiceLayer} suffix rule).
 */
@Component
public class RecipeIngredientGuard implements IngredientDeactivationGuard {

  private final RecipeReader reader;

  public RecipeIngredientGuard(RecipeReader reader) {
    this.reader = reader;
  }

  @Override
  public void checkDeactivate(UUID ingredientId) {
    if (reader.isIngredientReferenced(ingredientId)) {
      throw new IngredientInUseException(ingredientId, reader.referencingItemNames(ingredientId));
    }
  }
}
