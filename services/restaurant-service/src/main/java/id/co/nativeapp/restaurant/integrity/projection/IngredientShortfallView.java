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

  /**
   * The ingredient's BASE unit (g / ml / pcs) — the unit {@link #getMissingQty()} is counted in.
   * NOT the display label: the query selects {@code i.unit}, and calling this "display" would
   * invite a maintainer to swap in {@code i.display_unit} and turn 600 g missing into 600 kg.
   */
  String getUnit();

  /**
   * The label that sits ABOVE the base unit (kg over g, liter over ml), or {@code null} when the
   * base unit is what the console shows. Carried so the report renders the figure the way every
   * other stock surface does.
   */
  String getDisplayUnit();

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
