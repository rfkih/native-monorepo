package id.co.nativeapp.restaurant.recipe.projection;

import java.util.UUID;

/**
 * Depletion-path projection: every recipe line for a set of menu items, joined to the ingredient's
 * cost fields. ONE query serves both per-sale ingredient depletion (phase A) and per-sale COGS
 * computation (phase C reads {@code unitCostMinor}/{@code costCurrency} from the same rows).
 */
public interface RecipeDepletionRow {

  UUID getMenuItemId();

  UUID getIngredientId();

  UUID getModifierOptionId();

  int getQtyPerPortion();

  Long getUnitCostMinor();

  String getCostCurrency();
}
