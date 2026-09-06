package id.co.nativeapp.restaurant.integrity.projection;

/**
 * Read projection for the report's own blind-spot disclosure: of everything the outlet sold in the
 * window, how much of it was backed by a recipe at all.
 *
 * <p>An outlet with 20% recipe coverage can have a large leak that ingredient shrinkage will never
 * reveal, so a small estimate there means far less than the same estimate at 95% coverage. Stating
 * this is not a caveat — omitting it would let a reassuring number be read as a clean bill of
 * health.
 *
 * <p>Backs {@code SalesIntegrityRepository.findSoldItemCoverage}.
 */
public interface SoldItemCoverageView {

  /** Units sold in the window across every menu item. */
  long getTotalSoldQty();

  /** Of those, the units whose menu item has at least one recipe line. */
  long getRecipeBackedSoldQty();
}
