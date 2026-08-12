package id.co.nativeapp.restaurant.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Domain-unit tests for {@link Ingredient} (ADR 0046 phase 1): the always-tracked stock semantics
 * (setStock/addStock, floor-0 — {@code MenuItem#addStock} minus the untracked branch), the
 * both-or-neither cost-pair invariant, and PATCH-style partial updates.
 */
class IngredientTest {

  private static final UUID BUSINESS_ID = UUID.randomUUID();

  private Ingredient ingredient;

  @BeforeEach
  void setUp() {
    ingredient = new Ingredient(BUSINESS_ID, "Roti Burger", "pcs", null, null);
    ingredient.setCompanyId("11111111-1111-1111-1111-111111111111");
  }

  // -----------------------------------------------------------------------
  // Default state (newly created ingredient)
  // -----------------------------------------------------------------------

  @Test
  void newIngredientIsActiveWithZeroStockAndNoCost() {
    assertThat(ingredient.isActive()).isTrue();
    assertThat(ingredient.getStockQty()).isZero();
    assertThat(ingredient.getUnitCostMinor()).isNull();
    assertThat(ingredient.getCostCurrency()).isNull();
  }

  @Test
  void blankCostCurrencyWithNoAmountNormalizesToNull() {
    // Review finding (ADR 0046): a 3-space currency passes bean validation and counts as ABSENT
    // here — persisting the blank would violate the V31 both-or-neither CHECK (a 500).
    Ingredient blankCurrency = new Ingredient(BUSINESS_ID, "Selada", "g", null, "   ");
    assertThat(blankCurrency.getUnitCostMinor()).isNull();
    assertThat(blankCurrency.getCostCurrency()).isNull();
  }

  // -----------------------------------------------------------------------
  // setStock — ALWAYS tracked (unlike MenuItem, no null/untracked state)
  // -----------------------------------------------------------------------

  @Test
  void setStockWithPositiveValueUpdatesQuantity() {
    ingredient.setStock(50);
    assertThat(ingredient.getStockQty()).isEqualTo(50);
  }

  @Test
  void setStockWithZeroIsAllowed() {
    ingredient.setStock(0);
    assertThat(ingredient.getStockQty()).isZero();
  }

  @Test
  void setStockWithNegativeThrows() {
    assertThatThrownBy(() -> ingredient.setStock(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stock_qty must be >= 0");
  }

  // -----------------------------------------------------------------------
  // addStock — floor-0 logic, no untracked branch (ADR 0046)
  // -----------------------------------------------------------------------

  @Test
  void addStockIncreasesQuantity() {
    ingredient.setStock(10);
    ingredient.addStock(5);
    assertThat(ingredient.getStockQty()).isEqualTo(15);
  }

  @Test
  void addStockWithNegativeDeltaReducesQuantity() {
    ingredient.setStock(10);
    ingredient.addStock(-3);
    assertThat(ingredient.getStockQty()).isEqualTo(7);
  }

  @Test
  void addStockFloorsAtZeroOnLargeMagnitudeNegativeDelta() {
    ingredient.setStock(5);
    ingredient.addStock(-100);
    assertThat(ingredient.getStockQty()).isZero();
  }

  @Test
  void addStockOnAFreshIngredientWithZeroStockNeverThrows() {
    // Unlike MenuItem.addStock, there is no UntrackedStockException path — an ingredient is always
    // tracked, even at its default zero stock.
    ingredient.addStock(3);
    assertThat(ingredient.getStockQty()).isEqualTo(3);
  }

  // -----------------------------------------------------------------------
  // Cost pair — both-or-neither invariant
  // -----------------------------------------------------------------------

  @Test
  void constructingWithBothCostFieldsSetsTheCost() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    assertThat(costed.getUnitCostMinor()).isEqualTo(5_000L);
    assertThat(costed.getCostCurrency()).isEqualTo("IDR");
  }

  @Test
  void constructingWithOnlyUnitCostMinorThrows() {
    assertThatThrownBy(() -> new Ingredient(BUSINESS_ID, "Selada", "g", 1_000L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both be present or both absent");
  }

  @Test
  void constructingWithOnlyCostCurrencyThrows() {
    assertThatThrownBy(() -> new Ingredient(BUSINESS_ID, "Selada", "g", null, "IDR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both be present or both absent");
  }

  @Test
  void constructingWithNegativeUnitCostThrows() {
    assertThatThrownBy(() -> new Ingredient(BUSINESS_ID, "Patty", "pcs", -1L, "IDR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unitCostMinor must be >= 0");
  }

  @Test
  void constructingWithAnInvalidCurrencyCodeThrows() {
    assertThatThrownBy(() -> new Ingredient(BUSINESS_ID, "Patty", "pcs", 1_000L, "XXXX"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -----------------------------------------------------------------------
  // update — PATCH semantics
  // -----------------------------------------------------------------------

  @Test
  void updateAppliesOnlyNonNullFields() {
    ingredient.update("Roti Tawar", null, null, null, null);
    assertThat(ingredient.getName()).isEqualTo("Roti Tawar");
    assertThat(ingredient.getUnit()).isEqualTo("pcs");
  }

  @Test
  void updateWithNullUnitCostLeavesCostUnchanged() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    costed.update("Patty Sapi", "pack", null, null, null);
    assertThat(costed.getUnitCostMinor()).isEqualTo(5_000L);
    assertThat(costed.getCostCurrency()).isEqualTo("IDR");
  }

  @Test
  void updateWithBothCostFieldsSetsTheNewCost() {
    ingredient.update(null, null, null, 7_500L, "IDR");
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(7_500L);
    assertThat(ingredient.getCostCurrency()).isEqualTo("IDR");
  }

  // -----------------------------------------------------------------------
  // Moving weighted-average cost (V36) — stock_value_minor is the source of truth
  // -----------------------------------------------------------------------

  @Test
  void twoReceiptsAtDifferentPricesBlendToTheExactWeightedAverage() {
    // The headline case: 10.000 g flour @ 12/g then 10.000 g @ 13,5/g -> 255.000 over 20.000 g,
    // a true 12,75/g average. Value keeps the exact total; the unit-cost cache rounds (HALF_EVEN).
    ingredient.receive(10_000, 120_000L, "IDR"); // 12/g — establishes cost on the uncosted item
    ingredient.receive(10_000, 135_000L, "IDR"); // 13,5/g

    assertThat(ingredient.getStockQty()).isEqualTo(20_000);
    assertThat(ingredient.getStockValueMinor()).isEqualTo(255_000L);
    assertThat(ingredient.getCostCurrency()).isEqualTo("IDR");
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(13L); // round(12,75) HALF_EVEN
  }

  @Test
  void receiveWithAMismatchedCurrencyIsRejected() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    costed.setStock(10);
    assertThatThrownBy(() -> costed.receive(5, 30_000L, "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match ingredient cost currency");
  }

  @Test
  void receiveRejectsNonPositiveQuantityAndNegativeAmount() {
    assertThatThrownBy(() -> ingredient.receive(0, 1_000L, "IDR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("receive quantity must be positive");
    assertThatThrownBy(() -> ingredient.receive(5, -1L, "IDR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountPaidMinor must be >= 0");
  }

  @Test
  void receivingOntoAnUncostedIngredientWithStockValuesTheWholeStockAtTheReceiptPrice() {
    ingredient.setStock(5); // uncosted, no value bucket yet
    assertThat(ingredient.getStockValueMinor()).isZero();

    ingredient.receive(10, 130_000L, "IDR"); // 13.000/unit — pre-existing 5 are valued at it too

    assertThat(ingredient.getStockQty()).isEqualTo(15);
    assertThat(ingredient.getStockValueMinor()).isEqualTo(195_000L); // 15 × 13.000
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(13_000L);
  }

  @Test
  void aCostlessRecountPreservesTheUnitCostAndScalesValue() {
    ingredient.receive(20_000, 255_000L, "IDR"); // avg 12,75/g, value 255.000
    ingredient.setStock(10_000); // physical re-count to half — a sale/recount never moves the cost

    assertThat(ingredient.getStockValueMinor()).isEqualTo(127_500L); // value halves
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(13L); // average unchanged (127.500/10.000)
  }

  @Test
  void aCostlessAddValuesFoundStockAtTheCurrentAverage() {
    ingredient.receive(20_000, 255_000L, "IDR"); // avg 12,75/g
    ingredient.addStock(20_000); // found another 20.000 g — valued at the current average

    assertThat(ingredient.getStockQty()).isEqualTo(40_000);
    assertThat(ingredient.getStockValueMinor()).isEqualTo(510_000L); // 40.000 × 12,75
  }

  @Test
  void settingStockToZeroBooksAllValueOut() {
    ingredient.receive(20_000, 255_000L, "IDR");
    ingredient.setStock(0);
    assertThat(ingredient.getStockValueMinor()).isZero();
  }

  @Test
  void revalueSetsValueToQuantityTimesTheNewUnitCost() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    costed.setStock(10); // value = 10 × 5.000 = 50.000
    assertThat(costed.getStockValueMinor()).isEqualTo(50_000L);

    costed.update(null, null, null, 6_000L, "IDR"); // PATCH manual revalue

    assertThat(costed.getStockValueMinor()).isEqualTo(60_000L); // 10 × 6.000
    assertThat(costed.getUnitCostMinor()).isEqualTo(6_000L);
  }

  @Test
  void initialStockOnACostedIngredientEstablishesTheValueBucket() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    costed.setStock(30);
    assertThat(costed.getStockValueMinor()).isEqualTo(150_000L); // 30 × 5.000
    assertThat(costed.getUnitCostMinor()).isEqualTo(5_000L);
  }

  @Test
  void anUncostedIngredientNeverAccruesValue() {
    ingredient.setStock(100);
    ingredient.addStock(50);
    assertThat(ingredient.getStockQty()).isEqualTo(150);
    assertThat(ingredient.getStockValueMinor()).isZero();
    assertThat(ingredient.getUnitCostMinor()).isNull();
  }

  @Test
  void aFreeReceiptEstablishesAZeroCostCostedIngredient() {
    ingredient.receive(10, 0L, "IDR"); // received at no charge — still becomes a costed ingredient
    assertThat(ingredient.getStockQty()).isEqualTo(10);
    assertThat(ingredient.getStockValueMinor()).isZero();
    assertThat(ingredient.getCostCurrency()).isEqualTo("IDR");
    assertThat(ingredient.getUnitCostMinor()).isZero(); // round(0/10)
  }

  @Test
  void receivingAfterDepletingToZeroReestablishesValueOnTheCostedItem() {
    ingredient.receive(20_000, 255_000L, "IDR"); // avg 12,75/g
    ingredient.setStock(0); // fully consumed — value out, currency + cache retained
    assertThat(ingredient.getStockValueMinor()).isZero();

    ingredient.receive(10, 130_000L, "IDR"); // restock the now-empty costed item

    assertThat(ingredient.getStockQty()).isEqualTo(10);
    assertThat(ingredient.getStockValueMinor()).isEqualTo(130_000L);
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(13_000L);
  }

  @Test
  void aCostlessAddFromEmptyOnACostedIngredientValuesAtTheLastKnownCost() {
    // Exercises the applyCostlessQuantity empty-stock branch: a found-stock adjustment on a costed
    // but currently empty ingredient values the new units at the retained unit-cost cache.
    ingredient.receive(10, 130_000L, "IDR"); // cache 13.000
    ingredient.setStock(0); // qty 0, value 0, cache 13.000 retained
    ingredient.addStock(4); // costless from empty

    assertThat(ingredient.getStockQty()).isEqualTo(4);
    assertThat(ingredient.getStockValueMinor()).isEqualTo(52_000L); // 4 × 13.000
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(13_000L);
  }

  @Test
  void deactivateSetsActiveFalse() {
    ingredient.deactivate();
    assertThat(ingredient.isActive()).isFalse();
  }
}
