package id.co.nativeapp.restaurant.integrity.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one tracked menu item counted SHORT at a stocktake in the reported window —
 * the cleanest unrecorded-sale signal there is: a bottle that left the fridge with no sale behind
 * it is one unrecorded sale, at exactly one selling price.
 *
 * <p>Backs {@code SalesIntegrityRepository.findMissingTrackedItems}. Lives in the feature's
 * dedicated {@code projection} package (ArchUnit layer: service + repository only).
 */
public interface MissingTrackedItemView {

  UUID getMenuItemId();

  String getName();

  /** Units missing across every count in the window — POSITIVE (the query negates the variance). */
  long getMissingQty();

  /** The item's selling price in minor units, at read time. */
  long getUnitPriceMinor();

  String getCurrency();

  /** The most recent count in the window that found this item short. */
  Instant getLastCountedAt();
}
