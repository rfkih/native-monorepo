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
    ingredient.update("Roti Tawar", null, null, null);
    assertThat(ingredient.getName()).isEqualTo("Roti Tawar");
    assertThat(ingredient.getUnit()).isEqualTo("pcs");
  }

  @Test
  void updateWithNullUnitCostLeavesCostUnchanged() {
    Ingredient costed = new Ingredient(BUSINESS_ID, "Patty", "pcs", 5_000L, "IDR");
    costed.update("Patty Sapi", "pack", null, null);
    assertThat(costed.getUnitCostMinor()).isEqualTo(5_000L);
    assertThat(costed.getCostCurrency()).isEqualTo("IDR");
  }

  @Test
  void updateWithBothCostFieldsSetsTheNewCost() {
    ingredient.update(null, null, 7_500L, "IDR");
    assertThat(ingredient.getUnitCostMinor()).isEqualTo(7_500L);
    assertThat(ingredient.getCostCurrency()).isEqualTo("IDR");
  }

  @Test
  void deactivateSetsActiveFalse() {
    ingredient.deactivate();
    assertThat(ingredient.isActive()).isFalse();
  }
}
