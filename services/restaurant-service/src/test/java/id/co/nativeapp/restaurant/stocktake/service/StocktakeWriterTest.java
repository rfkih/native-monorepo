package id.co.nativeapp.restaurant.stocktake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.stocktake.domain.Stocktake;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeResult;
import id.co.nativeapp.restaurant.stocktake.projection.StocktakeView;
import id.co.nativeapp.restaurant.stocktake.repository.StocktakeLineRepository;
import id.co.nativeapp.restaurant.stocktake.repository.StocktakeRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for the {@link StocktakeWriter} money path (ADR 0038 phase 3): valued net shrinkage
 * ({@code −Σ variance×cost}), stock adjusted to the physical count, the outbox emit, strict
 * idempotency replay, and the per-line guards (untracked / duplicate / wrong outlet). Repository
 * calls are mocked; the SQL + RLS are exercised by the integration test.
 */
class StocktakeWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");

  private final StocktakeRepository repository = mock(StocktakeRepository.class);
  private final StocktakeLineRepository lineRepository = mock(StocktakeLineRepository.class);
  private final MenuItemRepository menuItemRepository = mock(MenuItemRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  private final StocktakeWriter writer =
      new StocktakeWriter(repository, lineRepository, menuItemRepository, outboxWriter, guard);

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private MenuItem trackedItem(int stock, Long unitCostMinor) {
    MenuItem item = new MenuItem(OUTLET, "Nasi Goreng", "Food", Money.ofMinor(25_000L, "IDR"));
    item.setStock(stock);
    item.setUnitCostMinor(unitCostMinor);
    item.setCompanyId(COMPANY);
    return item;
  }

  @Test
  void valuesNetShrinkageAndAdjustsStockToTheCount() {
    MenuItem item = trackedItem(20, 7_500L); // 20 in system, cost 7.5k
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(menuItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
    when(repository.saveAndFlush(any(Stocktake.class))).thenAnswer(inv -> inv.getArgument(0));

    SubmitStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitStocktakeRequest(
                        OUTLET, List.of(new StocktakeLineInput(item.getId(), 18))),
                    "k"));

    assertThat(result.created()).isTrue();
    // counted 18 − system 20 = −2 units short; valued −2 × 7.5k = −15k; shrinkage = net LOSS +15k
    assertThat(result.stocktake().shrinkageMinor()).isEqualTo(15_000L);
    assertThat(result.stocktake().lines()).hasSize(1);
    assertThat(result.stocktake().lines().get(0).varianceQty()).isEqualTo(-2);
    assertThat(result.stocktake().lines().get(0).varianceValueMinor()).isEqualTo(-15_000L);
    assertThat(item.getStockQuantity()).isEqualTo(18); // adjusted to the physical count
    verify(menuItemRepository).save(item);
    verify(lineRepository).save(any());
    verify(outboxWriter)
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
    verify(guard).enforce(OUTLET);
  }

  @Test
  void uncostedItemAdjustsStockButPostsNoValue() {
    MenuItem item = trackedItem(10, null); // no unit cost → operational count only
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(menuItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
    when(repository.saveAndFlush(any(Stocktake.class))).thenAnswer(inv -> inv.getArgument(0));

    SubmitStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitStocktakeRequest(
                        OUTLET, List.of(new StocktakeLineInput(item.getId(), 8))),
                    "k"));

    assertThat(result.stocktake().shrinkageMinor()).isZero();
    assertThat(result.stocktake().lines().get(0).varianceValueMinor()).isZero();
    assertThat(item.getStockQuantity()).isEqualTo(8); // still adjusted
    // The event still emits (zero shrinkage — the consumer no-ops but the stock was adjusted).
    verify(outboxWriter)
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void idempotencyReplayReturnsExistingWithoutReadjusting() {
    StocktakeView existing = mock(StocktakeView.class);
    when(existing.getId()).thenReturn(UUID.randomUUID());
    when(existing.getBusinessId()).thenReturn(OUTLET);
    when(existing.getCurrency()).thenReturn("IDR");
    when(existing.getShrinkageMinor()).thenReturn(0L);
    when(existing.getCountedAt()).thenReturn(Instant.now());
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.of(existing));
    when(lineRepository.findViewsByStocktakeId(any())).thenReturn(List.of());

    SubmitStocktakeResult result =
        asTenant(
            () ->
                writer.submit(
                    new SubmitStocktakeRequest(
                        OUTLET, List.of(new StocktakeLineInput(UUID.randomUUID(), 5))),
                    "k"));

    assertThat(result.created()).isFalse();
    verify(menuItemRepository, never()).save(any());
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void rejectsAnUntrackedItem() {
    MenuItem untracked = new MenuItem(OUTLET, "Untracked", "Food", Money.ofMinor(1_000L, "IDR"));
    untracked.setCompanyId(COMPANY); // stock_quantity stays null
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(menuItemRepository.findById(untracked.getId())).thenReturn(Optional.of(untracked));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitStocktakeRequest(
                                OUTLET, List.of(new StocktakeLineInput(untracked.getId(), 5))),
                            "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not tracked");
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  @Test
  void rejectsADuplicateMenuItemInTheRequest() {
    UUID id = UUID.randomUUID();
    when(repository.findViewByIdempotencyKey("k")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.submit(
                            new SubmitStocktakeRequest(
                                OUTLET,
                                List.of(
                                    new StocktakeLineInput(id, 5), new StocktakeLineInput(id, 6))),
                            "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    verify(menuItemRepository, never()).save(any());
  }
}
