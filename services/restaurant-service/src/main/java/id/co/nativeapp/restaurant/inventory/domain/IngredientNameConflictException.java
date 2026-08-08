package id.co.nativeapp.restaurant.inventory.domain;

/**
 * An ingredient with the same (case-insensitive) name already exists ACTIVE at this outlet — the
 * V31 partial unique index {@code uq_ingredient_company_business_name} rejected the write. A benign
 * everyday collision (re-adding "Roti"), so it maps to a clean {@code 409} rather than falling
 * through to the generic 500 handler (review finding, ADR 0046). Deactivating the old row frees
 * its name.
 */
public class IngredientNameConflictException extends RuntimeException {

  public IngredientNameConflictException(String name) {
    super("an active ingredient named '" + name + "' already exists at this outlet");
  }
}
