package id.co.nativeapp.restaurant.integrity.service;

import id.co.nativeapp.restaurant.integrity.domain.MixedCurrencyLeakReportException;
import id.co.nativeapp.restaurant.integrity.projection.RecipeConsumerView;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The money arithmetic behind the sales-leak estimate — pure, static, dependency-free, and
 * therefore the piece that gets pinned by unit tests rather than inferred from an integration run.
 *
 * <p><strong>Integer throughout (rule 8).</strong> No {@code double} appears anywhere, not even as
 * an intermediate weight. Proportions are carried as an explicit numerator and denominator of
 * {@code long}s and collapsed by a single integer division at the end, so the result is a
 * deterministic amount of minor units rather than something that depends on binary floating-point
 * rounding.
 */
public final class LeakEstimator {

  private LeakEstimator() {
    // pure functions
  }

  /**
   * One ingredient's shortfall turned into estimated lost revenue.
   *
   * @param estimatedRevenueMinor the revenue the missing quantity would have earned, or 0 when it
   *     could not be attributed to any menu item
   * @param attributable whether any recipe consumes this ingredient at all — a {@code false} here
   *     with a nonzero shortfall is a genuine blind spot (stock vanished and nothing on the menu
   *     admits to using it), not merely a zero
   */
  public record ShortfallEstimate(long estimatedRevenueMinor, boolean attributable) {}

  /**
   * Distributes one ingredient's missing quantity across the menu items whose recipes consume it,
   * in proportion to how the outlet's REAL sales mix consumed it over the window, and prices the
   * result.
   *
   * <p>The derivation, which is worth writing down because the final formula looks too simple:
   *
   * <pre>
   *   share of the shortfall owed to item i   w_i = sold_i * qpp_i / SUM_j(sold_j * qpp_j)
   *   quantity of the ingredient owed to i        = missing * w_i
   *   portions of i that quantity represents      = missing * w_i / qpp_i
   *                                               = missing * sold_i / SUM_j(sold_j * qpp_j)
   *   revenue                                     = that, times item i's price
   * </pre>
   *
   * <p>{@code qpp_i} cancels out of the numerator — but NOT out of the denominator, which is the
   * whole point: a dish that uses eight times as much of an ingredient absorbs eight times as much
   * of the shortfall at equal sales, yet each absorbed unit still converts back into portions at
   * that dish's own rate. Weighting by sales alone, or by recipe quantity alone, would get one half
   * of that right and the other half wrong.
   *
   * <p><strong>When nothing sold ({@code denominator == 0}).</strong> There is no sales mix to
   * weight by, so the estimate falls back on structure instead of behaviour: a single consumer
   * takes the whole shortfall (unambiguous — only one dish could have used it), several split the
   * QUANTITY evenly and each converts its share back into portions at its own rate. This is a
   * weaker estimate and it is deliberately not disguised as anything else; it only arises for an
   * ingredient whose every consumer was dormant, which is itself odd enough to look at.
   *
   * <p>Overflow is left to {@link Math#multiplyExact} rather than being silently truncated: an
   * arithmetic overflow here means an input so far outside plausible range that a wrapped,
   * confident wrong number would be considerably worse than a failed request.
   *
   * @param missingQty the shortfall, positive
   * @param consumers every base recipe line consuming this ingredient, with its window sales
   * @param expectedCurrency the currency every figure in this report must share, or {@code null} to
   *     adopt the first one seen
   * @return the estimate, never {@code null}
   * @throws MixedCurrencyLeakReportException if a consumer prices in a different currency
   */
  public static ShortfallEstimate estimateShortfallRevenue(
      long missingQty, List<RecipeConsumerView> consumers, @Nullable String expectedCurrency) {

    if (missingQty <= 0 || consumers.isEmpty()) {
      return new ShortfallEstimate(0L, !consumers.isEmpty());
    }

    long denominator = 0L;
    for (RecipeConsumerView c : consumers) {
      requireSameCurrency(expectedCurrency, c.getCurrency());
      if (c.getQtyPerPortion() > 0) {
        denominator =
            Math.addExact(denominator, Math.multiplyExact(c.getSoldQty(), c.getQtyPerPortion()));
      }
    }

    long revenue = 0L;
    if (denominator > 0) {
      for (RecipeConsumerView c : consumers) {
        if (c.getSoldQty() <= 0 || c.getQtyPerPortion() <= 0) {
          continue;
        }
        // missing * sold_i * price_i / denominator — multiplied first, divided once, so the
        // rounding happens exactly once instead of compounding across three steps.
        long numerator =
            Math.multiplyExact(
                Math.multiplyExact(missingQty, c.getSoldQty()), c.getUnitPriceMinor());
        revenue = Math.addExact(revenue, numerator / denominator);
      }
      return new ShortfallEstimate(revenue, true);
    }

    // Nothing sold in the window: fall back on structure.
    List<RecipeConsumerView> usable = new ArrayList<>();
    for (RecipeConsumerView c : consumers) {
      if (c.getQtyPerPortion() > 0) {
        usable.add(c);
      }
    }
    if (usable.isEmpty()) {
      return new ShortfallEstimate(0L, false);
    }
    long qtyEach = missingQty / usable.size();
    for (RecipeConsumerView c : usable) {
      long portions = qtyEach / c.getQtyPerPortion();
      revenue = Math.addExact(revenue, Math.multiplyExact(portions, c.getUnitPriceMinor()));
    }
    return new ShortfallEstimate(revenue, true);
  }

  /**
   * Units missing times the item's selling price. Trivial arithmetic, kept here so every money
   * figure in the report is produced by the same tested component rather than inline at a call
   * site.
   */
  public static long estimateTrackedItemRevenue(long missingQty, long unitPriceMinor) {
    if (missingQty <= 0 || unitPriceMinor <= 0) {
      return 0L;
    }
    return Math.multiplyExact(missingQty, unitPriceMinor);
  }

  /**
   * Groups recipe-consumer rows by the ingredient they consume, preserving query order within each
   * group so an estimate is reproducible run to run.
   */
  public static Map<UUID, List<RecipeConsumerView>> groupByIngredient(
      List<RecipeConsumerView> rows) {
    Map<UUID, List<RecipeConsumerView>> byIngredient = new LinkedHashMap<>();
    for (RecipeConsumerView row : rows) {
      byIngredient.computeIfAbsent(row.getIngredientId(), id -> new ArrayList<>()).add(row);
    }
    return byIngredient;
  }

  /**
   * Reconciles a currency against the report's, returning whichever is now established.
   *
   * <p>A {@code null} candidate is not a mismatch: an uncosted ingredient's stocktake carries no
   * currency at all, and that is an absence of information, not a conflicting one.
   */
  public static @Nullable String reconcileCurrency(
      @Nullable String established, @Nullable String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return established;
    }
    String trimmed = candidate.strip();
    if (established == null) {
      return trimmed;
    }
    if (!established.equals(trimmed)) {
      throw new MixedCurrencyLeakReportException(established, trimmed);
    }
    return established;
  }

  private static void requireSameCurrency(@Nullable String expected, @Nullable String actual) {
    if (expected != null && actual != null && !expected.equals(actual.strip())) {
      throw new MixedCurrencyLeakReportException(expected, actual.strip());
    }
  }
}
