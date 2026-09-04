package id.co.nativeapp.restaurant.inventory.projection;

import java.util.UUID;

/**
 * Read projection for one ingredient's consumed quantity on one day ("terpakai") — backs the native
 * usage-by-date query on {@code IngredientUsageDayRepository}. Lives in the feature's dedicated
 * {@code projection} package (ArchUnit layer: service + repository only).
 */
public interface IngredientUsageView {

  UUID getIngredientId();

  long getQtyUsed();
}
