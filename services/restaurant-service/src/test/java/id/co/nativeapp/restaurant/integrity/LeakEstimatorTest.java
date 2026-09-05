package id.co.nativeapp.restaurant.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.integrity.domain.MixedCurrencyLeakReportException;
import id.co.nativeapp.restaurant.integrity.projection.RecipeConsumerView;
import id.co.nativeapp.restaurant.integrity.service.LeakEstimator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link LeakEstimator} — the arithmetic that turns a missing quantity of an
 * ingredient into an estimate of the revenue it would have earned (ADR 0074).
 *
 * <p>This is the component worth pinning precisely: every headline figure the report shows an owner
 * comes out of these few lines, the allocation is easy to get subtly wrong in a way no integration
 * test would notice, and the numbers here are checked by hand against the derivation in the
 * estimator's own javadoc.
 */
class LeakEstimatorTest {

  private static final UUID INGREDIENT = UUID.fromString("11111111-0000-0000-0000-000000000001");

  @Test
  void aSingleConsumerConvertsTheWholeShortfallAtItsOwnRecipeRate() {
    // 600 g of rice missing; one dish uses 200 g per portion and sells for 25k.
    List<RecipeConsumerView> consumers = List.of(consumer("Nasi Goreng", 25_000L, 200, 40));

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(600, consumers, "IDR");

    // 600 / 200 = 3 portions x 25k = 75k. No allocation ambiguity: only one dish uses rice.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(75_000L);
    assertThat(estimate.attributable()).isTrue();
  }

  @Test
  void severalConsumersSplitTheShortfallByHowTheSalesMixActuallyConsumedIt() {
    // Both dishes sold 10 units, but the second uses four times as much of the ingredient, so it
    // absorbs four times as much of the shortfall — and converts its share back into portions at
    // its own heavier rate, which is the half a sales-only weighting would get wrong.
    List<RecipeConsumerView> consumers =
        List.of(
            consumer("Ayam Kecil", 20_000L, 100, 10), // 10 x 100 = 1000
            consumer("Ayam Besar", 50_000L, 400, 10)); // 10 x 400 = 4000, denominator 5000

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(1_000, consumers, "IDR");

    // portions_i = missing * sold_i / denominator = 1000 * 10 / 5000 = 2 each.
    // revenue = 2 x 20k + 2 x 50k = 140k.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(140_000L);
    // Sanity: those 2+2 portions consume 2*100 + 2*400 = 1000 units — exactly the shortfall.
    assertThat(estimate.attributable()).isTrue();
  }

  @Test
  void aDormantConsumerContributesNothingButStillAnchorsTheDenominator() {
    // One dish sold, one did not. All of the shortfall lands on the one that actually sold.
    List<RecipeConsumerView> consumers =
        List.of(
            consumer("Terjual", 30_000L, 150, 20), //
            consumer("Tidak terjual", 90_000L, 150, 0));

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(450, consumers, "IDR");

    // denominator = 20*150 = 3000; portions = 450*20/3000 = 3 -> 3 x 30k = 90k. The dormant
    // dish's much higher price never inflates the estimate, because nothing suggests it sold.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(90_000L);
  }

  @Test
  void whenNothingSoldASingleConsumerStillGivesAnUnambiguousAnswer() {
    List<RecipeConsumerView> consumers = List.of(consumer("Soto", 18_000L, 250, 0));

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(1_000, consumers, "IDR");

    // No sales mix to weight by, but only one dish could have used it: 1000/250 = 4 x 18k = 72k.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(72_000L);
    assertThat(estimate.attributable()).isTrue();
  }

  @Test
  void whenNothingSoldSeveralConsumersSplitTheQuantityEvenly() {
    List<RecipeConsumerView> consumers =
        List.of(consumer("A", 10_000L, 100, 0), consumer("B", 40_000L, 200, 0));

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(1_000, consumers, "IDR");

    // 500 units each: A -> 5 portions x 10k = 50k, B -> 2 portions (500/200 floored) x 40k = 80k.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(130_000L);
  }

  @Test
  void anIngredientNoRecipeUsesIsReportedAsUnattributableRatherThanAsZeroRevenue() {
    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(5_000, List.of(), "IDR");

    // The distinction matters: 0 revenue reads as "nothing was lost", while unattributable says
    // "stock vanished and the menu cannot account for it" — a blind spot, not a clean result.
    assertThat(estimate.estimatedRevenueMinor()).isZero();
    assertThat(estimate.attributable()).isFalse();
  }

  @Test
  void aNonPositiveShortfallEstimatesNothing() {
    List<RecipeConsumerView> consumers = List.of(consumer("Apa saja", 10_000L, 100, 10));

    assertThat(LeakEstimator.estimateShortfallRevenue(0, consumers, "IDR").estimatedRevenueMinor())
        .isZero();
    assertThat(LeakEstimator.estimateShortfallRevenue(-5, consumers, "IDR").estimatedRevenueMinor())
        .isZero();
  }

  @Test
  void theResultRoundsOnceAtTheEndRatherThanCompoundingPerStep() {
    // denominator = 10 * 30 = 300; 100 * 10 * 9_999 / 300 = 9_999_000 / 300 = 33_330 exactly.
    List<RecipeConsumerView> consumers = List.of(consumer("Pecahan", 9_999L, 30, 10));

    LeakEstimator.ShortfallEstimate estimate =
        LeakEstimator.estimateShortfallRevenue(100, consumers, "IDR");

    // Dividing per-step instead of once at the end would floor the portion count to 0 and report
    // nothing at all; multiplying first keeps the sub-portion value.
    assertThat(estimate.estimatedRevenueMinor()).isEqualTo(33_330L);
  }

  @Test
  void aConsumerPricedInAnotherCurrencyFailsClosedRatherThanBeingSummed() {
    List<RecipeConsumerView> consumers =
        List.of(consumer("Rupiah", 10_000L, 100, 5), consumerIn("Dolar", 10_000L, 100, 5, "USD"));

    assertThatThrownBy(() -> LeakEstimator.estimateShortfallRevenue(500, consumers, "IDR"))
        .isInstanceOf(MixedCurrencyLeakReportException.class)
        .hasMessageContaining("IDR")
        .hasMessageContaining("USD");
  }

  @Test
  void trackedItemRevenueIsUnitsTimesPriceAndNeverNegative() {
    assertThat(LeakEstimator.estimateTrackedItemRevenue(12, 15_000L)).isEqualTo(180_000L);
    assertThat(LeakEstimator.estimateTrackedItemRevenue(0, 15_000L)).isZero();
    assertThat(LeakEstimator.estimateTrackedItemRevenue(-3, 15_000L)).isZero();
    assertThat(LeakEstimator.estimateTrackedItemRevenue(12, 0L)).isZero();
  }

  @Test
  void anAbsentCurrencyIsMissingInformationNotAConflict() {
    // An uncosted ingredient's stocktake carries no currency at all; that must not be mistaken for
    // a disagreement, or every uncosted outlet would 422 instead of reporting.
    assertThat(LeakEstimator.reconcileCurrency("IDR", null)).isEqualTo("IDR");
    assertThat(LeakEstimator.reconcileCurrency("IDR", "  ")).isEqualTo("IDR");
    assertThat(LeakEstimator.reconcileCurrency(null, "IDR")).isEqualTo("IDR");
    assertThat(LeakEstimator.reconcileCurrency(null, null)).isNull();
    assertThatThrownBy(() -> LeakEstimator.reconcileCurrency("IDR", "USD"))
        .isInstanceOf(MixedCurrencyLeakReportException.class);
  }

  @Test
  void groupingKeepsEveryEdgeUnderItsOwnIngredient() {
    UUID other = UUID.fromString("11111111-0000-0000-0000-000000000002");
    RecipeConsumerView a = consumer("A", 1_000L, 10, 1);
    RecipeConsumerView b = consumer("B", 2_000L, 20, 2);
    RecipeConsumerView c = consumerOf(other, "C", 3_000L, 30, 3, "IDR");

    var grouped = LeakEstimator.groupByIngredient(List.of(a, b, c));

    assertThat(grouped).hasSize(2);
    assertThat(grouped.get(INGREDIENT)).containsExactly(a, b);
    assertThat(grouped.get(other)).containsExactly(c);
  }

  private static RecipeConsumerView consumer(
      String name, long priceMinor, long qtyPerPortion, long soldQty) {
    return consumerOf(INGREDIENT, name, priceMinor, qtyPerPortion, soldQty, "IDR");
  }

  private static RecipeConsumerView consumerIn(
      String name, long priceMinor, long qtyPerPortion, long soldQty, String currency) {
    return consumerOf(INGREDIENT, name, priceMinor, qtyPerPortion, soldQty, currency);
  }

  /** A hand-rolled projection stub — the interface has eight accessors and no behaviour to mock. */
  private static RecipeConsumerView consumerOf(
      UUID ingredientId,
      String name,
      long priceMinor,
      long qtyPerPortion,
      long soldQty,
      String currency) {
    return new RecipeConsumerView() {
      @Override
      public UUID getIngredientId() {
        return ingredientId;
      }

      @Override
      public UUID getMenuItemId() {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public long getUnitPriceMinor() {
        return priceMinor;
      }

      @Override
      public String getCurrency() {
        return currency;
      }

      @Override
      public long getQtyPerPortion() {
        return qtyPerPortion;
      }

      @Override
      public long getSoldQty() {
        return soldQty;
      }
    };
  }
}
