package id.co.nativeapp.restaurant.integrity.projection;

import java.util.UUID;

/**
 * Read projection for how many units of one menu item sold in the window — the sales mix every
 * shortfall allocation is weighted by.
 *
 * <p>Fetched ONCE per report and joined against the recipe edges in memory. It is the same roll-up
 * the coverage figure needs, so computing it once serves both and keeps the two numbers derived
 * from an identical population: a coverage percentage measured over a different denominator than
 * the estimate it qualifies would quietly misdescribe it.
 *
 * <p>Backs {@code SalesIntegrityRepository.findSoldQuantities}.
 */
public interface SoldQuantityView {

  UUID getMenuItemId();

  long getSoldQty();
}
