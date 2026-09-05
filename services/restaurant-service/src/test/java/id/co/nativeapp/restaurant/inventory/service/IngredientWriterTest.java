package id.co.nativeapp.restaurant.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceipt;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.domain.IngredientUnitChangeException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.repository.GoodsReceiptRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStockDayRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link IngredientWriter} (ADR 0046 phase 1): create stamps {@code company_id}, the
 * set/add stock paths, the not-found 404 path, and the {@link OutletAccessGuard} call on every
 * mutation. Repository + guard are mocked; SQL + RLS are exercised by the integration tests. The
 * ADR 0067 Phase B goods-receipt/outbox side effect is covered by a dedicated Testcontainers
 * atomicity test (real DB + real outbox insert), so {@link #goodsReceiptRepository} / {@link
 * #outboxWriter} here are mocked no-ops.
 */
class IngredientWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");

  private final IngredientRepository repository = mock(IngredientRepository.class);
  private final GoodsReceiptRepository goodsReceiptRepository = mock(GoodsReceiptRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  // The V47 daily-ledger UPSERTs are a mocked no-op here: their SQL is exercised by the
  // Testcontainers tests.
  private final IngredientStockDayRepository stockDayRepository =
      mock(IngredientStockDayRepository.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  // No deactivation guards in the unit pins — the ADR 0050 recipe veto is integration-tested.
  // The applier is REAL and wraps the same mocks, so the existing verify()/verifyNoInteractions()
  // assertions keep observing the receive/receipt/outbox writes unchanged (the ADR 0071
  // GeneralLedgerWriter test-construction precedent).
  private final IngredientWriter writer =
      new IngredientWriter(
          repository,
          stockDayRepository,
          goodsReceiptRepository,
          new PricedReceiveWriter(
              repository, stockDayRepository, goodsReceiptRepository, outboxWriter),
          guard,
          java.util.List.of());

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
                    new CreateIngredientRequest(OUTLET, "Roti", "pcs", null, null, null, null)));

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
                    new CreateIngredientRequest(OUTLET, "Roti", "pcs", null, 2_000L, "IDR", 30)));

    assertThat(response.stockQty()).isEqualTo(30);
    assertThat(response.unitCostMinor()).isEqualTo(2_000L);
  }

  @Test
  void createStoresTheDefaultPackSize() {
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(
            () ->
                writer.create(
                    new CreateIngredientRequest(
                        OUTLET, "Tortilla", "pcs", null, null, null, null, 20)));

    assertThat(response.packSize()).isEqualTo(20);
  }

  @Test
  void aNonPositivePackSizeIsRejected() {
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.create(
                            new CreateIngredientRequest(
                                OUTLET, "Tortilla", "pcs", null, null, null, null, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Owner request 2026-09-04 — the guard behind "kg bisa desimal": an item mis-created as `pcs` has
   * to become g/kg, and rewriting the unit under existing stock would reinterpret "10 pcs" as "10
   * g" and poison the moving-average value built from it.
   */
  @Test
  void changingTheBaseUnitIsRefusedWhileStockRemains() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.update(
                            ingredient.getId(),
                            new UpdateIngredientRequest(null, "g", "kg", null, null))))
        .isInstanceOf(IngredientUnitChangeException.class)
        .hasMessageContaining("still holds stock");
  }

  /**
   * Review finding M1 — zero stock is NOT enough on its own. {@code unit_cost_minor} is a
   * PER-BASE-UNIT figure that survives the zeroing (the cache recompute is a no-op at qty 0), and
   * the from-empty revaluation prices new stock at it. Left behind, "Rp 50 per pcs" would silently
   * become "per gram" and the next 2 kg would book 1000x its real value. A base-unit change must
   * therefore reset every per-unit figure hanging off the old unit.
   */
  @Test
  void changingTheBaseUnitIsAllowedOnceStockIsZeroAndResetsThePerUnitFigures() {
    Ingredient ingredient = tracked(0); // "Patty", pcs, Rp 50/pcs
    ingredient.setPackSize(20);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(
            () ->
                writer.update(
                    ingredient.getId(), new UpdateIngredientRequest(null, "g", "kg", null, null)));

    assertThat(response.unit()).isEqualTo("g");
    assertThat(response.displayUnit())
        .as("kg display over a g base — decimals now parse")
        .isEqualTo("kg");
    assertThat(response.unitCostMinor()).as("a per-pcs cost cannot mean per-gram").isNull();
    assertThat(response.costCurrency()).isNull();
    assertThat(response.packSize()).as("pack size is stored in BASE units").isNull();

    // The poisoning path itself: stocking up after the switch must NOT revalue at the old cost.
    ingredient.setStock(2_000); // 2 kg
    assertThat(ingredient.getStockValueMinor())
        .as("uncosted after the unit change — a priced receive re-establishes the cost")
        .isZero();
  }

  @Test
  void aPatchSetsClearsOrLeavesThePackSizeAlone() {
    Ingredient ingredient = tracked(5);
    ingredient.setPackSize(20);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThat(
            asTenant(
                    () ->
                        writer.update(
                            ingredient.getId(),
                            new UpdateIngredientRequest("Patty Sapi", null, null, null, null)))
                .packSize())
        .as("both fields absent -> untouched")
        .isEqualTo(20);

    assertThat(
            asTenant(
                    () ->
                        writer.update(
                            ingredient.getId(),
                            new UpdateIngredientRequest(null, null, null, null, null, 12, null)))
                .packSize())
        .isEqualTo(12);

    assertThat(
            asTenant(
                    () ->
                        writer.update(
                            ingredient.getId(),
                            new UpdateIngredientRequest(null, null, null, null, null, null, true)))
                .packSize())
        .as("clearPackSize -> removed")
        .isNull();
  }

  /** Only the DISPLAY label changing is always safe — the base quantity is untouched by it. */
  @Test
  void addingADisplayUnitOverTheSameBaseIsAllowedWithStock() {
    Ingredient ingredient = new Ingredient(OUTLET, "Tepung", "g", 10L, "IDR");
    ingredient.setStock(2_500);
    ingredient.setCompanyId(COMPANY);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(
            () ->
                writer.update(
                    ingredient.getId(), new UpdateIngredientRequest(null, "g", "kg", null, null)));

    assertThat(response.stockQty()).isEqualTo(2_500);
    assertThat(response.displayUnit()).isEqualTo("kg");
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
        asTenant(() -> writer.addStock(ingredient.getId(), 5, null, null, null));

    assertThat(response.stockQty()).isEqualTo(15);
  }

  @Test
  void addStockWithNegativeDeltaFloorsAtZero() {
    Ingredient ingredient = tracked(3);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(() -> writer.addStock(ingredient.getId(), -100, null, null, null));

    assertThat(response.stockQty()).isZero();
  }

  @Test
  void addStockWithAPriceReceivesAndReblendsTheMovingAverage() {
    // tracked(10) is 10 units @ 5_000 => value 50_000. Receive 10 more for a TOTAL of 130_000
    // (13_000/unit) => value 180_000 over 20 units => a blended 9_000/unit.
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));
    when(repository.saveAndFlush(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));
    when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    IngredientResponse response =
        asTenant(() -> writer.addStock(ingredient.getId(), 10, 130_000L, "IDR", null));

    assertThat(response.stockQty()).isEqualTo(20);
    assertThat(response.stockValueMinor()).isEqualTo(180_000L);
    assertThat(response.unitCostMinor()).isEqualTo(9_000L);

    // ADR 0067 Phase B: the priced receive also records the goods-receipt fact + outbox event, in
    // this same transaction — the atomicity test exercises the real DB path; here we pin that the
    // writer at least attempts both.
    verify(goodsReceiptRepository).saveAndFlush(any(GoodsReceipt.class));
    verify(outboxWriter)
        .write(
            org.mockito.ArgumentMatchers.eq("goods_receipt"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("StockReceived"),
            org.mockito.ArgumentMatchers.any(byte[].class),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(UUID.fromString(COMPANY)),
            org.mockito.ArgumentMatchers.any(java.time.Instant.class));
  }

  @Test
  void addStockWithOnlyOnePriceFieldIsRejected() {
    Ingredient ingredient = tracked(10);
    when(repository.findById(ingredient.getId())).thenReturn(Optional.of(ingredient));

    assertThatThrownBy(
            () -> asTenant(() -> writer.addStock(ingredient.getId(), 10, 130_000L, null, null)))
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
