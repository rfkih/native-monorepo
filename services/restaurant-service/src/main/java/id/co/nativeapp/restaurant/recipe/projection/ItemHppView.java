package id.co.nativeapp.restaurant.recipe.projection;

import java.util.UUID;

/**
 * HPP-summary projection: one row per BASE recipe line (option deltas are excluded from the
 * headline HPP — they price the customization, not the standard portion), joined to ingredient cost
 * fields. {@code RecipeReader} folds these into per-item HPP + completeness in Java, where the
 * currency-match rules live.
 */
public interface ItemHppView {

  UUID getMenuItemId();

  int getQtyPerPortion();

  Long getUnitCostMinor();

  String getCostCurrency();
}
