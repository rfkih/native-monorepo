package id.co.nativeapp.restaurant.recipe.domain;

/**
 * A menu item cannot be 1:1 auto-linked ("Lacak stok") because an ACTIVE ingredient with the same
 * name already exists at the outlet but is NOT a whole-piece ({@code pcs}) item — e.g. a raw
 * material tracked in grams. Linking it 1-per-portion would silently deplete/​revalue that raw
 * material on every sale (review W2), and minting a duplicate is impossible (the V31 name unique).
 * The owner must give the menu item a distinct name or build a real recipe. Mapped to {@code 422}
 * ({@code recipe-auto-link-blocked}) by {@code config.RecipeAdvice}.
 */
public class RecipeAutoLinkBlockedException extends RuntimeException {

  public RecipeAutoLinkBlockedException(String itemName, String existingUnit) {
    super(
        "cannot auto-link \""
            + itemName
            + "\": a same-named ingredient tracked in '"
            + existingUnit
            + "' already exists — rename the item or build a recipe");
  }
}
