package id.co.nativeapp.restaurant.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktake;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStocktakeView;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStocktakeLineRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStocktakeRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeCurrencyMismatchException;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for the {@link IngredientStocktakeWriter} money path (ADR 0046 phase 1): valued net
 * shrinkage, stock adjusted to the physical count, no-cost submissions emit no event, single
 * cost-currency enforcement, and the per-line guards (wrong outlet / inactive / duplicate).
 * Repository calls are mocked; the SQL + RLS are exercised by the integration tests.
 */
class IngredientStocktakeWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");
  private static final UUID OTHER_OUTLET = UUID.fromString("6a6f1278-ff81-56c9-9bff-12af9230f76a");

  private final IngredientStocktakeRepository repository =
      mock(IngredientStocktakeRepository.class);
  private final IngredientStocktakeLineRepository lineRepository =
      mock(IngredientStocktakeLineRepository.class);
  private final IngredientRepository ingredientRepository = mock(IngredientRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  private final IngredientStocktakeWriter writer =
      new IngredientStocktakeWriter(
          repository, lineRepository, ingredientRepository, outboxWriter, guard);

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Ingredient costedIngredient(int stock, long unitCostMinor) {
    Ingredient ingredient = new Ingredient(OUTLET, "Patty", "pcs", unitCostMinor, "IDR");
    ingredient.setStock(stock);
    ingredient.setCompanyId(COMPANY);
    return ingredient;
  }

  private Ingredient uncostedIngredient(int stock) {
    Ingredient ingredient = new Ingredient(OUTLET, "Selada", "g", null, null);
    ingredient.setStock(stock);
    ingredient.setCompanyId(COMPANY);
    return ingredient;
  }

  @Test
  void valuesNetShrinkageAndAdjustsStockToTheCount() {
    Ingredient ingredient = costedIngredient(20, 7_500L); // 20 in system, cost 7.5k
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(IngredientStocktake.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredient.getId(), 18))),
                    "k"));

    assertThat(result.created()).isTrue();
    // counted 18 − system 20 = −2 units short; valued −2 × 7.5k = −15k; shrinkage = net LOSS +15k
    assertThat(result.stocktake().shrinkageMinor()).isEqualTo(15_000L);
    assertThat(result.stocktake().currency()).isEqualTo("IDR");
    assertThat(result.stocktake().lines()).hasSize(1);
    assertThat(result.stocktake().lines().get(0).varianceQty()).isEqualTo(-2);
    assertThat(result.stocktake().lines().get(0).varianceValueMinor()).isEqualTo(-15_000L);
    assertThat(ingredient.getStockQty()).isEqualTo(18); // adjusted to the physical count
    verify(ingredientRepository).save(ingredient);
    verify(lineRepository).save(any());
    verify(outboxWriter)
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
    verify(guard).enforce(OUTLET);
  }

  @Test
  void uncostedIngredientAdjustsStockButEmitsNoEvent() {
    Ingredient ingredient = uncostedIngredient(10); // no unit cost → operational count only
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(IngredientStocktake.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredient.getId(), 8))),
                    "k"));

    assertThat(result.stocktake().currency()).isNull();
    assertThat(result.stocktake().shrinkageMinor()).isZero();
    assertThat(result.stocktake().lines().get(0).varianceValueMinor()).isZero();
    assertThat(ingredient.getStockQty()).isEqualTo(8); // still adjusted
    // ADR 0046: zero costed lines ⇒ NO outbox write at all.
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void aMixOfCostedAndUncostedLinesStillEmitsOneEventValuedFromTheCostedLineOnly() {
    Ingredient costed = costedIngredient(20, 7_500L);
    Ingredient uncosted = uncostedIngredient(5);
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(costed.getId())).thenReturn(Optional.of(costed));
    when(ingredientRepository.findById(uncosted.getId())).thenReturn(Optional.of(uncosted));
    when(repository.saveAndFlush(any(IngredientStocktake.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET,
                        List.of(
                            new IngredientStocktakeLineInput(costed.getId(), 18),
                            new IngredientStocktakeLineInput(uncosted.getId(), 5))),
                    "k"));

    assertThat(result.stocktake().currency()).isEqualTo("IDR");
    assertThat(result.stocktake().shrinkageMinor()).isEqualTo(15_000L);
    verify(outboxWriter)
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void idempotencyReplayReturnsExistingWithoutReadjusting() {
    IngredientStocktakeView existing = mock(IngredientStocktakeView.class);
    when(existing.getId()).thenReturn(UUID.randomUUID());
    when(existing.getBusinessId()).thenReturn(OUTLET);
    when(existing.getCurrency()).thenReturn("IDR");
    when(existing.getShrinkageMinor()).thenReturn(0L);
    when(existing.getCountedAt()).thenReturn(Instant.now());
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.of(existing));
    when(lineRepository.findViewsByStocktakeId(any())).thenReturn(List.of());

    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(UUID.randomUUID(), 5))),
                    "k"));

    assertThat(result.created()).isFalse();
    verify(ingredientRepository, never()).save(any());
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void rejectsAnIngredientFromAnotherOutlet() {
    Ingredient wrongOutlet = new Ingredient(OTHER_OUTLET, "Saus", "ml", null, null);
    wrongOutlet.setCompanyId(COMPANY);
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(wrongOutlet.getId())).thenReturn(Optional.of(wrongOutlet));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitIngredientStocktakeRequest(
                                OUTLET,
                                List.of(new IngredientStocktakeLineInput(wrongOutlet.getId(), 5))),
                            "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in outlet");
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void rejectsAnInactiveIngredient() {
    Ingredient inactive = new Ingredient(OUTLET, "Bawang", "g", null, null);
    inactive.setCompanyId(COMPANY);
    inactive.deactivate();
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitIngredientStocktakeRequest(
                                OUTLET,
                                List.of(new IngredientStocktakeLineInput(inactive.getId(), 5))),
                            "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not active");
  }

  @Test
  void rejectsADuplicateIngredientInTheRequest() {
    UUID id = UUID.randomUUID();
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitIngredientStocktakeRequest(
                                OUTLET,
                                List.of(
                                    new IngredientStocktakeLineInput(id, 5),
                                    new IngredientStocktakeLineInput(id, 6))),
                            "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    verify(ingredientRepository, never()).save(any());
  }

  @Test
  void rejectsMismatchedCostCurrenciesAcrossCostedLines() {
    Ingredient idrLine = costedIngredient(10, 5_000L);
    Ingredient usdLine = new Ingredient(OUTLET, "Imported Cheese", "g", 200L, "USD");
    usdLine.setStock(10);
    usdLine.setCompanyId(COMPANY);
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(ingredientRepository.findById(idrLine.getId())).thenReturn(Optional.of(idrLine));
    when(ingredientRepository.findById(usdLine.getId())).thenReturn(Optional.of(usdLine));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitIngredientStocktakeRequest(
                                OUTLET,
                                List.of(
                                    new IngredientStocktakeLineInput(idrLine.getId(), 9),
                                    new IngredientStocktakeLineInput(usdLine.getId(), 9))),
                            "k")))
        .isInstanceOf(StocktakeCurrencyMismatchException.class);
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }
}
