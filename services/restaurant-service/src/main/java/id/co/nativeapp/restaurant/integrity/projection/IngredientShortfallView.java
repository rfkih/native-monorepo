package id.co.nativeapp.restaurant.integrity.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one ingredient counted SHORT at a stock opname in the reported window.
 *
 * <p>Carries BOTH figures the report needs, which are not the same thing: {@link
 * #getMissingCostMinor()} is what the vanished stock cost — already exact, moving-average valued
 * (ADR 0056) — while the revenue it would have earned is an ESTIMATE the service derives from the
 * recipes that consume it. Reporting only the second would overstate certainty; only the first
 * would understate the loss.
 *
 * <p>Backs {@code SalesIntegrityRepository.findIngredientShortfalls}.
 */
public interface IngredientShortfallView {

  UUID getIngredientId();

  String getName();

  /** The ingredient's display unit (g / ml / pcs) — opaque text, no conversion. */
  String getUnit();

  /** Quantity missing across every count in the window — POSITIVE (the query negates it). */
  long getMissingQty();

  /**
   * What that missing quantity cost, in minor units — POSITIVE. Zero for an uncosted ingredient,
   * which contributes nothing to a valued figure but still carries a quantity.
   */
  long getMissingCostMinor();

  /** The stocktake's cost currency, or {@code null} when nothing in it carried a cost. */
  String getCurrency();

  Instant getLastCountedAt();
}
