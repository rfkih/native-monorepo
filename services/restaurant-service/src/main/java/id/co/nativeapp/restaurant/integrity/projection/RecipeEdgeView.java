package id.co.nativeapp.restaurant.integrity.projection;

import java.util.UUID;

/**
 * Read projection for one (ingredient → menu item) BASE recipe edge: which dish consumes the
 * ingredient, how much of it a portion takes, and what the dish sells for.
 *
 * <p>Structure only. The sales mix that weights an allocation across these edges is fetched
 * separately and once ({@link SoldQuantityView}) rather than embedded here, so it is not re-scanned
 * for every chunk of a large {@code IN} clause.
 *
 * <p>Backs {@code SalesIntegrityRepository.findRecipeEdges}. Lives in the feature's dedicated
 * {@code projection} package (ArchUnit layer: service + repository only).
 */
public interface RecipeEdgeView {

  UUID getIngredientId();

  UUID getMenuItemId();

  String getName();

  long getUnitPriceMinor();

  String getCurrency();

  /** How much of this ingredient one portion consumes. Always positive on a base line. */
  long getQtyPerPortion();
}
