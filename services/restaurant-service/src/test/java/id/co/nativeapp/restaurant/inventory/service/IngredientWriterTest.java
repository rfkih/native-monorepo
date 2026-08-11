package id.co.nativeapp.restaurant.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link IngredientWriter} (ADR 0046 phase 1): create stamps {@code company_id}, the
 * set/add stock paths, the not-found 404 path, and the {@link OutletAccessGuard} call on every
 * mutation. Repository + guard are mocked; SQL + RLS are exercised by the integration tests.
 */
class IngredientWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");

  private final IngredientRepository repository = mock(IngredientRepository.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  // No deactivation guards in the unit pins — the ADR 0050 recipe veto is integration-tested.
  private final IngredientWriter writer =
      new IngredientWriter(repository, guard, java.util.List.of());

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Ingredient tracked(int stock) {
    Ingredient ingredient = new Ingredient(OUTLET, "Patty", "pcs", 5_000L, "IDR");
    ingredient.setStock(stock);
    ingredient.setCompanyId(COMPANY);
    return ingredient;
  }

  @Test
  void createStampsTheCompanyIdFromTheBoundTenant() {
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(
            () ->
                writer.create(
                    new CreateIngredientRequest(OUTLET, "Roti", "pcs", null, null, null)));

    assertThat(response.name()).isEqualTo("Roti");
    assertThat(response.stockQty()).isZero();
    verify(guard).enforce(OUTLET);
  }

  @Test
  void createAppliesTheInitialStockQty() {
    // Review finding (ADR 0046): a dropped opening quantity books the whole first count as a
    // phantom inventory GAIN on a costed ingredient — the seed must actually persist.
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(
            () ->
                writer.create(
                    new CreateIngredientRequest(OUTLET, "Roti", "pcs", 2_000L, "IDR", 30)));

    assertThat(response.stockQty()).isEqualTo(30);
    assertThat(response.unitCostMinor()).isEqualTo(2_000L);
  }

  @Test
  void setStockUpdatesTheAbsoluteQuantity() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response = asTenant(() -> writer.setStock(ingredient.getId(), 25));

    assertThat(response.stockQty()).isEqualTo(25);
    verify(guard).enforce(OUTLET);
  }

  @Test
  void setStockOnAMissingIngredientThrowsNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> asTenant(() -> writer.setStock(id, 5)))
        .isInstanceOf(IngredientNotFoundException.class);
  }

  @Test
  void addStockIncrementsTheCurrentQuantity() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(() -> writer.addStock(ingredient.getId(), 5, null, null));

    assertThat(response.stockQty()).isEqualTo(15);
  }

  @Test
  void addStockWithNegativeDeltaFloorsAtZero() {
    Ingredient ingredient = tracked(3);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(() -> writer.addStock(ingredient.getId(), -100, null, null));

    assertThat(response.stockQty()).isZero();
  }

  @Test
  void addStockWithAPriceReceivesAndReblendsTheMovingAverage() {
    // tracked(10) is 10 units @ 5_000 => value 50_000. Receive 10 more for a TOTAL of 130_000
    // (13_000/unit) => value 180_000 over 20 units => a blended 9_000/unit.
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(() -> writer.addStock(ingredient.getId(), 10, 130_000L, "IDR"));

    assertThat(response.stockQty()).isEqualTo(20);
    assertThat(response.stockValueMinor()).isEqualTo(180_000L);
    assertThat(response.unitCostMinor()).isEqualTo(9_000L);
  }

  @Test
  void addStockWithOnlyOnePriceFieldIsRejected() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));

    assertThatThrownBy(
            () -> asTenant(() -> writer.addStock(ingredient.getId(), 10, 130_000L, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both be present or both absent");
  }

  @Test
  void deactivateSetsActiveFalse() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    asTenant(
        () -> {
          writer.deactivate(ingredient.getId());
          return null;
        });

    assertThat(ingredient.isActive()).isFalse();
  }
}
