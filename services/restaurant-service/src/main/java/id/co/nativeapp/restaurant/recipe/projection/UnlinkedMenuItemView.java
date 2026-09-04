package id.co.nativeapp.restaurant.recipe.projection;

import java.util.UUID;

/**
 * Read projection for the auto-link sweep ("Lacak stok otomatis"): an ACTIVE menu item that has NO
 * recipe lines yet — the id to link and the name to mint its 1:1 ingredient from. Backs {@code
 * RecipeLineRepository#findUnlinkedActiveItems}.
 */
public interface UnlinkedMenuItemView {

  UUID getId();

  String getName();
}
