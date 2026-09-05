package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.domain.StockLedgerDay;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStockDayResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStockSummaryResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of the V47 daily stock ledger against real Postgres: every way an
 * ingredient's stock can move lands in the RIGHT bucket of {@code ingredient_stock_day}, the
 * per-(ingredient, day) UPSERT accumulates instead of overwriting, {@code closing_qty} always
 * mirrors the stock figure after the movement, and the roll-up read reproduces the totals.
 *
 * <p>The bucket split is the whole point: a leak report subtracts already-explained movement
 * (receipts, hand-corrections, opname variance) from what physically vanished, so a receipt booked
 * as a correction — or vice versa — would silently change the answer.
 */
@SpringBootTest
class IngredientStockDayLedgerTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "cashier-stock-ledger@example.co.id";
  private static final UUID OUTLET = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Autowired private IngredientService ingredientService;
  @Autowired private IngredientStocktakeService stocktakeService;

  @Test
  void everyKindOfStockMovementLandsInItsOwnBucketAndAccumulatesOnOneDayRow() {
    // 1. Opening stock is stock ARRIVING, so it is a receipt — not a correction.
    UUID ingredientId =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            OUTLET, "Ayam", "pcs", null, 12_000L, "IDR", 100, null))
                    .id());

    // 2. A costless positive delta is a second receipt: the quantity adds, the COUNT increments,
    //    so "300 arrived in one delivery" stays distinguishable from "300 across two".
    asTenant(TENANT, () -> ingredientService.addStock(ingredientId, 40, null, null, null));

    // 3. A manual "set stok" is the archetypal correction — signed, and counted.
    asTenant(TENANT, () -> ingredientService.setStock(ingredientId, 120));

    // 4. An opname line is the same act by another route: a human overriding the system figure.
    asTenant(
        TENANT,
        () ->
            stocktakeService.submit(
                new SubmitIngredientStocktakeRequest(
                    OUTLET, List.of(new IngredientStocktakeLineInput(ingredientId, 115))),
                "stock-ledger-opname-key"));

    LocalDate today = StockLedgerDay.today();
    List<IngredientStockDayResponse> ledger =
        asTenant(TENANT, () -> ingredientService.stockHistory(ingredientId, today, today));

    // All four movements happened on the same ledger day, so the UPSERT accumulated them onto ONE
    // row rather than overwriting — that is the property the ON CONFLICT clause exists for.
    assertThat(ledger).hasSize(1);
    IngredientStockDayResponse day = ledger.getFirst();
    assertThat(day.stockDate()).isEqualTo(today);
    assertThat(day.receivedQty()).isEqualTo(140); // 100 opening + 40 added
    assertThat(day.receiptCount()).isEqualTo(2);
    assertThat(day.adjustmentQty()).isEqualTo(-25); // 140 -> 120 (-20), then 120 -> 115 (-5)
    assertThat(day.adjustmentCount()).isEqualTo(2);
    assertThat(day.qtyUsed()).isZero(); // nothing was sold
    assertThat(day.wasteQty()).isZero(); // reserved until the waste log lands
    // closing_qty mirrors the stock figure after the LAST movement, not the first.
    assertThat(day.closingQty()).isEqualTo(115L);
  }

  @Test
  void theRollUpReproducesTheDayTotalsAndReportsCountsRatherThanAnAverage() {
    UUID ingredientId =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            OUTLET, "Beras", "g", null, null, null, 5_000, null))
                    .id());
    asTenant(TENANT, () -> ingredientService.setStock(ingredientId, 4_800));

    LocalDate today = StockLedgerDay.today();
    List<IngredientStockSummaryResponse> summary =
        asTenant(TENANT, () -> ingredientService.stockSummary(OUTLET, today.minusDays(7), today));

    assertThat(summary).hasSize(1);
    IngredientStockSummaryResponse row = summary.getFirst();
    assertThat(row.ingredientId()).isEqualTo(ingredientId);
    assertThat(row.name()).isEqualTo("Beras");
    assertThat(row.unit()).isEqualTo("g");
    assertThat(row.totalReceivedQty()).isEqualTo(5_000);
    assertThat(row.receiptCount()).isEqualTo(1);
    assertThat(row.netAdjustmentQty()).isEqualTo(-200);
    assertThat(row.adjustmentCount()).isEqualTo(1);
    // Nothing was sold, so the ingredient moved on one day but consumed on none — the two
    // denominators a caller might divide by are reported separately, and no average is computed
    // server-side.
    assertThat(row.totalUsedQty()).isZero();
    assertThat(row.daysWithMovement()).isEqualTo(1);
    assertThat(row.daysWithUsage()).isZero();
    assertThat(row.latestClosingQty()).isEqualTo(4_800L);
  }

  @Test
  void anotherTenantSeesNoneOfTheseLedgerRows() {
    asTenant(
        TENANT,
        () ->
            ingredientService
                .create(
                    new CreateIngredientRequest(
                        OUTLET, "Minyak", "ml", null, null, null, 2_000, null))
                .id());

    LocalDate today = StockLedgerDay.today();
    // Same outlet id, different company: the FORCE-RLS policy is what has to hide these rows, not
    // any predicate in the query — none of the ledger queries carries a company_id clause.
    List<IngredientStockSummaryResponse> summary =
        asTenant(
            OTHER_TENANT, () -> ingredientService.stockSummary(OUTLET, today.minusDays(7), today));

    assertThat(summary).isEmpty();
  }

  private static <T> T asTenant(String tenant, java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(tenant, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
