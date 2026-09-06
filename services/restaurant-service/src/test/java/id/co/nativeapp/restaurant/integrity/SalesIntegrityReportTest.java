package id.co.nativeapp.restaurant.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.integrity.domain.LeakSignalType;
import id.co.nativeapp.restaurant.integrity.dto.LeakSignalResponse;
import id.co.nativeapp.restaurant.integrity.dto.SalesIntegrityReportResponse;
import id.co.nativeapp.restaurant.integrity.service.SalesIntegrityReader;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.StockService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of the sales-leak report (ADR 0074) against real Postgres: the detectors
 * fire on a deliberately planted leak, the money lands where it should in the low/high range, the
 * coverage block tells the truth about what was NOT seen, and another tenant sees none of it.
 *
 * <p>Everything is seeded through the real services rather than by direct SQL, so the test
 * exercises the same stocktake, recipe and checkout paths production writes through — a report
 * built on hand-inserted rows could pass while the real write path stores something the queries
 * never match.
 */
@SpringBootTest
class SalesIntegrityReportTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_TENANT = "33333333-3333-3333-3333-333333333333";
  private static final String ACTOR = "owner-leak-report@example.co.id";

  @Autowired private SalesIntegrityReader reader;
  @Autowired private MenuService menuService;
  @Autowired private StockService stockService;
  @Autowired private StocktakeService stocktakeService;
  @Autowired private IngredientService ingredientService;
  @Autowired private IngredientStocktakeService ingredientStocktakeService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;

  @Test
  void trackedItemsCountedShortArePricedAtTheirSellingPriceAndSetTheConfidentFloor() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    // A bottled drink at 15k with 20 in stock, counted at 18 — two left the fridge unsold.
    UUID itemId =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(outlet, "Teh Botol", "DRINK", 15_000L, "IDR"))
                    .id());
    asTenant(TENANT, () -> stockService.setStock(itemId, 20));
    asTenant(
        TENANT,
        () ->
            stocktakeService.submit(
                new SubmitStocktakeRequest(outlet, List.of(new StocktakeLineInput(itemId, 18))),
                "leak-item-count-" + outlet));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    LeakSignalResponse signal =
        signalOf(report, LeakSignalType.MISSING_TRACKED_ITEMS).orElseThrow();
    assertThat(signal.occurrences()).isEqualTo(2); // two units, not two items
    assertThat(signal.estimatedValueMinor()).isEqualTo(30_000L);
    assertThat(signal.currency()).isEqualTo("IDR");
    assertThat(signal.details())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.subjectName()).isEqualTo("Teh Botol");
              assertThat(d.quantity()).isEqualTo(2L);
              assertThat(d.valueMinor()).isEqualTo(30_000L);
            });

    // A tracked-item shortfall is tightly quantified, so it raises the CONFIDENT floor — not just
    // the upper bound. This is the one signal where low and high move together.
    assertThat(report.estimatedLeakMinorLow()).isEqualTo(30_000L);
    assertThat(report.estimatedLeakMinorHigh()).isEqualTo(30_000L);
    assertThat(report.currency()).isEqualTo("IDR");
  }

  @Test
  void anIngredientShortfallBecomesEstimatedRevenueThroughTheRecipeButOnlyRaisesTheUpperBound() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    // 2000 g of rice at 10/g; one dish uses 200 g and sells for 25k.
    UUID riceId =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Beras-" + outlet, "g", "kg", 10L, "IDR", 2_000, null))
                    .id());
    UUID dishId =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(outlet, "Nasi Goreng", "MAIN", 25_000L, "IDR"))
                    .id());
    asTenant(
        TENANT,
        () -> {
          recipeService.putRecipe(
              dishId, new PutRecipeRequest(List.of(new RecipeLineInput(riceId, null, 200))));
          return null;
        });

    // Five portions are rung honestly: 1000 g depleted, 1000 g should remain.
    asTenant(
        TENANT,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet, "leak-sale-" + outlet, List.of(new OrderLineRequest(dishId, 5)))));

    // The count finds only 400 g — 600 g gone with no sale behind it: three unrung portions.
    asTenant(
        TENANT,
        () ->
            ingredientStocktakeService.submit(
                new SubmitIngredientStocktakeRequest(
                    outlet, List.of(new IngredientStocktakeLineInput(riceId, 400))),
                "leak-ingredient-count-" + outlet));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    LeakSignalResponse signal = signalOf(report, LeakSignalType.INGREDIENT_SHORTFALL).orElseThrow();
    // 600 g is the only consumer's, so 600/200 = 3 portions x 25k = 75k.
    assertThat(signal.estimatedValueMinor()).isEqualTo(75_000L);
    assertThat(signal.occurrences()).isEqualTo(1);
    // The evidence row states the unit the shortfall is counted in. Without it "600 missing" is
    // ambiguous by a factor of a thousand for an ingredient the stock page shows in kg.
    assertThat(signal.details())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.quantity()).isEqualTo(600L);
              assertThat(d.quantityUnit()).isEqualTo("g");
            });

    // The COST of what vanished is not an estimate — 600 g at 10/g, already computed by the
    // stocktake at the moving-average rate.
    assertThat(report.confirmedMissingCostMinor()).isEqualTo(6_000L);

    // And the crucial asymmetry: an ingredient estimate has innocent explanations Native cannot yet
    // record (waste, staff meals), so it moves the ceiling and leaves the floor alone.
    assertThat(report.estimatedLeakMinorLow()).isZero();
    assertThat(report.estimatedLeakMinorHigh()).isEqualTo(75_000L);
  }

  @Test
  void salesRungWithNoRegisterSessionOpenAreReportedWithTheirValue() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    UUID itemId =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(outlet, "Kopi", "DRINK", 12_000L, "IDR"))
                    .id());
    asTenant(
        TENANT,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet, "leak-nosession-" + outlet, List.of(new OrderLineRequest(itemId, 2)))));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    // No session was ever opened at this outlet, so the sale is unsessioned by definition — the
    // drawer it went into is never counted against what it took.
    LeakSignalResponse signal =
        signalOf(report, LeakSignalType.SALES_OUTSIDE_SESSION).orElseThrow();
    assertThat(signal.occurrences()).isEqualTo(1);
    // 2 x 12k, plus the outlet's service charge and PB1: the figure is the GRAND TOTAL the till
    // actually took, not net sales. That is the right number here — what went unreconciled is the
    // cash in the drawer, tax and service charge included, not the revenue line under it.
    assertThat(signal.estimatedValueMinor()).isEqualTo(27_720L);

    // But TODAY is NOT reported as a day that never closed, even though it has taken money and has
    // no Z-report. A day in progress has not failed to close — it has not finished. The console
    // asks for the whole current month, so its window end is in the FUTURE, and without capping the
    // day set at the last COMPLETE outlet-local day every outlet would be told every single day
    // that today went unreconciled.
    assertThat(signalOf(report, LeakSignalType.TRADING_DAY_WITHOUT_CLOSE)).isEmpty();

    // Same reasoning, same root cause: tonight has not happened, so it cannot be a dark hour, and
    // a session opened minutes ago has not been abandoned.
    assertThat(signalOf(report, LeakSignalType.DARK_HOUR)).isEmpty();
    assertThat(signalOf(report, LeakSignalType.SESSION_LEFT_OPEN)).isEmpty();

    // Neither is counted into the leak range: they describe money that went UNRECONCILED, not money
    // that is missing. Folding them in would double-count the honest takings of a sloppy shift.
    assertThat(report.estimatedLeakMinorLow()).isZero();
    assertThat(report.estimatedLeakMinorHigh()).isZero();

    // And nobody is named. One person did all of this, so there is no rest-of-outlet to compare
    // them against — an owner who works their own till must never be reported as an anomaly
    // against themselves. This guards the WIRING of that rule, which OperatorOutliersTest pins in
    // isolation.
    assertThat(signalOf(report, LeakSignalType.HIGH_VOID_RATE)).isEmpty();
    assertThat(signalOf(report, LeakSignalType.HIGH_REFUND_RATE)).isEmpty();
    assertThat(signalOf(report, LeakSignalType.HIGH_DISCOUNT_RATE)).isEmpty();
    assertThat(signalOf(report, LeakSignalType.CASH_TENDER_SKEW)).isEmpty();
  }

  @Test
  void anOperatorDetailCountsEVENTSNeverMoney() {
    // The discount signal's rate numerator is an AMOUNT in minor units. If that reached `quantity`
    // the row would render "25000" beside the same figure formatted as "Rp 25.000" — one fact
    // shown twice, once mislabelled and off by the currency's exponent. Whatever an operator row
    // reports as a quantity must be a count of times, which is what every signal here means.
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    for (LeakSignalResponse signal : report.signals()) {
      for (var detail : signal.details()) {
        if (detail.quantity() != null) {
          assertThat(detail.quantity())
              .as("%s quantity must be a count, not an amount", signal.type())
              .isLessThan(1_000_000L);
        }
      }
    }
  }

  @Test
  void theCoverageBlockReportsWhatTheReportCouldNotSee() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    UUID withRecipe =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(outlet, "Mie", "MAIN", 20_000L, "IDR"))
                    .id());
    UUID withoutRecipe =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(outlet, "Es Teh", "DRINK", 5_000L, "IDR"))
                    .id());
    UUID noodles =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Mie kering-" + outlet, "g", null, null, null, 5_000, null))
                    .id());
    asTenant(
        TENANT,
        () -> {
          recipeService.putRecipe(
              withRecipe, new PutRecipeRequest(List.of(new RecipeLineInput(noodles, null, 100))));
          return null;
        });

    // 3 units of a recipe-backed dish, 7 of an item nothing knows the ingredients of.
    asTenant(
        TENANT,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet,
                    "leak-coverage-" + outlet,
                    List.of(
                        new OrderLineRequest(withRecipe, 3),
                        new OrderLineRequest(withoutRecipe, 7)))));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    assertThat(report.coverage().totalSoldQty()).isEqualTo(10);
    // Only 3 of 10 units sold could ever be reached by ingredient-shortfall detection. An owner
    // reading a small estimate at this coverage is reading almost nothing, and must be told so.
    assertThat(report.coverage().recipeBackedSoldQty()).isEqualTo(3);
    // Never counted is reported as null, not as 0 days — "counted today" is a different claim.
    assertThat(report.coverage().daysSinceIngredientCount()).isNull();
    assertThat(report.coverage().daysSinceItemCount()).isNull();
    // Creating the ingredient with opening stock is a RECEIPT, not a correction (V47 bucketing).
    assertThat(report.coverage().manualStockCorrections()).isZero();
  }

  @Test
  void aWindowWithNothingWrongReturnsNoSignalsRatherThanNineZeroes() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    SalesIntegrityReportResponse report = report(TENANT, outlet, from);

    // An empty list says "nothing stood out". Nine signals each reporting zero would say "the
    // system looked and found nothing", which is a weaker and noisier claim.
    assertThat(report.signals()).isEmpty();
    assertThat(report.estimatedLeakMinorLow()).isZero();
    assertThat(report.estimatedLeakMinorHigh()).isZero();
    assertThat(report.currency()).isNull();
  }

  @Test
  void anotherTenantSeesNoneOfAnotherCompanysLeak() {
    UUID outlet = UUID.randomUUID();
    Instant from = Instant.now().minus(Duration.ofDays(1));

    UUID itemId =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(outlet, "Rahasia", "MAIN", 99_000L, "IDR"))
                    .id());
    asTenant(TENANT, () -> stockService.setStock(itemId, 10));
    asTenant(
        TENANT,
        () ->
            stocktakeService.submit(
                new SubmitStocktakeRequest(outlet, List.of(new StocktakeLineInput(itemId, 4))),
                "leak-rls-" + outlet));

    // Same outlet id, different company. Nothing in these queries carries a company_id predicate —
    // the FORCE-RLS policies are what must hide this, and a leak report leaking across tenants
    // would be considerably worse than one that under-reports.
    SalesIntegrityReportResponse report = report(OTHER_TENANT, outlet, from);

    assertThat(report.signals()).isEmpty();
    assertThat(report.estimatedLeakMinorHigh()).isZero();
    assertThat(report.coverage().totalSoldQty()).isZero();
  }

  private SalesIntegrityReportResponse report(String tenant, UUID outlet, Instant from) {
    return asTenant(tenant, () -> reader.report(outlet, from, Instant.now().plusSeconds(60)));
  }

  private static Optional<LeakSignalResponse> signalOf(
      SalesIntegrityReportResponse report, LeakSignalType type) {
    return report.signals().stream().filter(s -> s.type() == type).findFirst();
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
