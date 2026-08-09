package id.co.nativeapp.restaurant.recipe.projection;

import java.util.UUID;

/**
 * Read-path projection of one {@code recipe_line} joined to its ingredient's display fields (rule:
 * reads select only the columns they need — never {@code SELECT *} of the entity). Snake_case
 * aliases → camelCase getters per the house convention.
 */
public interface RecipeLineView {

  UUID getId();

  UUID getIngredientId();

  String getIngredientName();

  String getUnit();

  UUID getModifierOptionId();

  int getQtyPerPortion();

  Long getUnitCostMinor();

  String getCostCurrency();

  boolean getIngredientActive();
}
