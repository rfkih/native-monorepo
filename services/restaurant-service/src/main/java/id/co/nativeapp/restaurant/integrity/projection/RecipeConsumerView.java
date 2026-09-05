package id.co.nativeapp.restaurant.integrity.projection;

import java.util.UUID;

/**
 * Read projection for one (ingredient → menu item) recipe edge, carrying how much of that item
 * actually SOLD in the reported window.
 *
 * <p>This is what turns a missing quantity of an ingredient into an estimated number of portions: a
 * kilo of unaccounted-for rice becomes revenue only once you know which dishes consume rice, how
 * much each takes, and how the outlet's real sales mix was weighted that week.
 *
 * <p>Only BASE recipe lines ({@code modifier_option_id IS NULL}) participate. Per-option deltas are
 * deliberately excluded: they are signed adjustments to a specific order's portion, and attributing
 * a shortfall through them would require knowing which options were chosen on the sales that were
 * never recorded — which is precisely what is unknown.
 *
 * <p>Backs {@code SalesIntegrityRepository.findRecipeConsumers}.
 */
public interface RecipeConsumerView {

  UUID getIngredientId();

  UUID getMenuItemId();

  String getName();

  long getUnitPriceMinor();

  String getCurrency();

  /**
   * How much of this ingredient one portion of the item consumes. Always positive on a base line.
   */
  long getQtyPerPortion();

  /** Units of this item sold in the window, across both the order and the bill paths. Can be 0. */
  long getSoldQty();
}
