package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.domain.IngredientUnitConversionException;
import id.co.nativeapp.restaurant.inventory.domain.StockLedgerDay;
import id.co.nativeapp.restaurant.inventory.dto.ConvertIngredientUnitRequest;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStockDayResponse;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of the ingredient unit conversion against real Postgres.
 *
 * <p>The operation exists because a base unit can be too coarse to consume from: an ingredient
 * created as {@code pack} cannot appear in a recipe at all — a pack has nothing beneath it, so the
 * smallest expressible use is one whole pack. In production that is exactly what happened to the
 * sauce, and to every weight ingredient in a different way.
 *
 * <p>What these tests are really guarding is that the conversion moves EVERYTHING at once.
 * Converting the ingredient but not its recipes would leave every portion consuming a thousandth of
 * what it should; converting the recipes but not the ledger would leave "average consumption per
 * day" averaging grams against packs across the conversion date. Both failures are silent — they
 * surface as an inexplicable variance at the next stock count, which the leak report would then
 * read as theft.
 */
@SpringBootTest
class IngredientUnitConversionTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "owner-unit-convert@example.co.id";

  @Autowired private IngredientService ingredientService;
  @Autowired private MenuService menuService;
  @Autowired private RecipeService recipeService;

  @Test
  void convertingAPackToGramsRescalesStockCostRecipesAndTheLedgerTogether() {
    UUID outlet = UUID.randomUUID();

    // A sauce bought by the pack: 2 packs at Rp 30.000 each. Creating it with opening stock also
    // writes a ledger receipt (V47), which is what gives the conversion history to rescale.
    UUID sauceId =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Saus-" + outlet, "pack", null, 30_000L, "IDR", 2, null))
                    .id());

    // The recipe an owner COULD write before the conversion: one whole pack per portion, which is
    // absurd but the only thing the unit allows. This is the line that must be rescaled, not left.
    UUID dishId =
        asTenant(
            TENANT,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(outlet, "Kebab", "MAIN", 30_000L, "IDR"))
                    .id());
    asTenant(
        TENANT,
        () -> {
          recipeService.putRecipe(
              dishId, new PutRecipeRequest(List.of(new RecipeLineInput(sauceId, null, 1))));
          return null;
        });

    IngredientResponse converted =
        asTenant(
            TENANT,
            () ->
                ingredientService.convertUnit(
                    sauceId, new ConvertIngredientUnitRequest("g", "kg", 1_000)));

    // Stock is MULTIPLIED, not reinterpreted: 2 packs of 1000 g are 2000 g, the same sauce.
    assertThat(converted.unit()).isEqualTo("g");
    assertThat(converted.stockQty()).isEqualTo(2_000);
    // Per-unit cost divides, so a gram costs 30 rupiah where a pack cost 30.000.
    assertThat(converted.unitCostMinor()).isEqualTo(30L);
    // And the invariant that makes the whole thing safe: total VALUE never moved. Nothing was
    // bought, sold or lost — only the unit changed.
    assertThat(converted.stockValueMinor()).isEqualTo(60_000L);

    // The recipe moved with it. Had this been left at 1, every kebab would consume ONE GRAM of
    // sauce instead of a pack's worth — a thousandfold under-consumption that would only surface as
    // an unexplained surplus at the next opname.
    var recipe = asTenant(TENANT, () -> recipeService.getRecipe(dishId));
    assertThat(recipe.lines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.ingredientId()).isEqualTo(sauceId);
              assertThat(l.qtyPerPortion()).isEqualTo(1_000);
            });

    // And so did the ledger, so consumption history is not half in packs and half in grams.
    LocalDate today = StockLedgerDay.today();
    List<IngredientStockDayResponse> ledger =
        asTenant(TENANT, () -> ingredientService.stockHistory(sauceId, today, today));
    assertThat(ledger)
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.receivedQty()).isEqualTo(2_000);
              assertThat(d.closingQty()).isEqualTo(2_000L);
            });
  }

  @Test
  void aConversionThatWouldOverflowTheStockColumnIsRefused() {
    UUID outlet = UUID.randomUUID();
    // 1.7 tonnes of meat in grams — the real production figure. Converting THAT to milligrams would
    // need 1.7 billion, which does not fit the INTEGER holding it.
    UUID meatId =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Daging-" + outlet, "g", "kg", 100L, "IDR", 1_700_000, null))
                    .id());

    assertThatThrownBy(
            () ->
                asTenant(
                    TENANT,
                    () ->
                        ingredientService.convertUnit(
                            meatId, new ConvertIngredientUnitRequest("mg", null, 10_000))))
        .isInstanceOf(IngredientUnitConversionException.class)
        .hasMessageContaining("overflow");

    // Refused BEFORE any mutation — a truncated stock figure would read as a catastrophic shrinkage
    // at the next count and, through the leak report, as theft.
    IngredientResponse unchanged =
        asTenant(
            TENANT,
            () ->
                ingredientService.findByBusiness(outlet).stream()
                    .filter(i -> i.id().equals(meatId))
                    .findFirst()
                    .orElseThrow());
    assertThat(unchanged.unit()).isEqualTo("g");
    assertThat(unchanged.stockQty()).isEqualTo(1_700_000);
  }

  @Test
  void aNonPositiveFactorIsRefused() {
    UUID outlet = UUID.randomUUID();
    UUID id =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Apa-" + outlet, "pack", null, null, null, 1, null))
                    .id());

    assertThatThrownBy(
            () ->
                asTenant(
                    TENANT,
                    () ->
                        ingredientService.convertUnit(
                            id, new ConvertIngredientUnitRequest("g", null, 0))))
        .isInstanceOf(IngredientUnitConversionException.class);
  }

  @Test
  void anUncostedIngredientConvertsWithoutInventingACost() {
    UUID outlet = UUID.randomUUID();
    UUID id =
        asTenant(
            TENANT,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Tanpa harga-" + outlet, "pack", null, null, null, 3, null))
                    .id());

    IngredientResponse converted =
        asTenant(
            TENANT,
            () ->
                ingredientService.convertUnit(
                    id, new ConvertIngredientUnitRequest("g", "kg", 500)));

    assertThat(converted.stockQty()).isEqualTo(1_500);
    // No cost went in, so none comes out — a conversion must not manufacture a valuation.
    assertThat(converted.unitCostMinor()).isNull();
    assertThat(converted.stockValueMinor()).isZero();
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
