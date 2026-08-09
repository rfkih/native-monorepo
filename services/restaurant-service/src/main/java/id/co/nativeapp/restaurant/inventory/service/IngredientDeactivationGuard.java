package id.co.nativeapp.restaurant.inventory.service;

import java.util.UUID;

/**
 * A veto hook consulted by {@link IngredientWriter#deactivate} before soft-deleting an ingredient.
 * Defined HERE (inventory) and implemented by downstream features — dependency inversion so the
 * inventory feature never imports its consumers (the recipe feature implements this to block
 * deactivating an ingredient that menu-item recipes still reference, ADR 0050).
 */
public interface IngredientDeactivationGuard {

  /**
   * Throws a feature-specific runtime exception (mapped by that feature's advice) when the
   * ingredient must not be deactivated; returns normally otherwise. Runs inside the deactivation
   * transaction — the tenant GUC is already bound.
   */
  void checkDeactivate(UUID ingredientId);
}
