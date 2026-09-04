package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientUsageResponse;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.recipe.domain.RecipeAutoLinkBlockedException;
import id.co.nativeapp.restaurant.recipe.dto.AutoLinkResult;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of "Lacak stok otomatis" (the 1:1 auto-link) and the
 * per-day usage aggregate (V42): auto-link mints a same-named pcs ingredient + a 1-per-portion base
 * line for every recipe-less item (idempotent; existing recipes untouched; same-named ingredients
 * reused), and — end to end — selling a linked item through the REAL checkout depletes the
 * ingredient's stock (the opname prefill figure) AND accumulates "terpakai" for the outlet-local
 * day. Tenant isolation of the usage read is pinned too.
 */
@SpringBootTest
class RecipeAutoLinkIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "owner-autolink@example.co.id";

  /** Must mirror IngredientDepletionWriter.USAGE_ZONE (the register business-date convention). */
  private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Jakarta");

  @Autowired private MenuService menuService;
  @Autowired private RecipeService recipeService;
  @Autowired private IngredientService ingredientService;
  @Autowired private OrderService orderService;

  private static <T> T asA(Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT_A, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createItem(UUID outlet, String name) {
    return asA(
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(outlet, name, "FOOD", 30_000L, "IDR"))
                .id());
  }

  private IngredientResponse ingredientNamed(UUID outlet, String name) {
    return asA(() -> ingredientService.findByBusiness(outlet)).stream()
        .filter(i -> i.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void autoLinkAllMintsOneToOneIngredientsSkipsRecipesAndIsIdempotent() {
    UUID outlet = UUID.fromString("ee500001-1111-1111-1111-111111111111");
    UUID burger = createItem(outlet, "Burger");
    UUID kebab = createItem(outlet, "Kebab");
    // A third item with a MANUAL recipe already — the sweep must never touch it.
    UUID esTeh = createItem(outlet, "Es Teh");
    UUID tehIngredient =
        asA(
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            outlet, "Teh celup", "pcs", null, null, null, 100))
                    .id());
    asA(
        () ->
            recipeService.putRecipe(
                esTeh, new PutRecipeRequest(List.of(new RecipeLineInput(tehIngredient, null, 2)))));

    AutoLinkResult first = asA(() -> recipeService.autoLinkAll(outlet));
    assertThat(first.linkedCount()).isEqualTo(2); // Burger + Kebab
    assertThat(first.skippedCount()).isEqualTo(1); // Es Teh already had a recipe

    // Each linked item now has ONE base line of 1/portion onto a same-named pcs ingredient.
    RecipeResponse burgerRecipe = asA(() -> recipeService.getRecipe(burger));
    assertThat(burgerRecipe.lines()).hasSize(1);
    assertThat(burgerRecipe.lines().get(0).qtyPerPortion()).isEqualTo(1);
    assertThat(burgerRecipe.lines().get(0).ingredientName()).isEqualTo("Burger");
    IngredientResponse burgerIngredient = ingredientNamed(outlet, "Burger");
    assertThat(burgerIngredient.unit()).isEqualTo("pcs");
    assertThat(burgerIngredient.stockQty()).isZero();
    assertThat(ingredientNamed(outlet, "Kebab").unit()).isEqualTo("pcs");

    // Es Teh's manual recipe is untouched (still the 2-per-portion Teh celup line).
    RecipeResponse esTehRecipe = asA(() -> recipeService.getRecipe(esTeh));
    assertThat(esTehRecipe.lines()).hasSize(1);
    assertThat(esTehRecipe.lines().get(0).ingredientId()).isEqualTo(tehIngredient);
    assertThat(esTehRecipe.lines().get(0).qtyPerPortion()).isEqualTo(2);

    // Idempotent: a second sweep links nothing and mints nothing.
    AutoLinkResult second = asA(() -> recipeService.autoLinkAll(outlet));
    assertThat(second.linkedCount()).isZero();
    long burgerNamedCount =
        asA(() -> ingredientService.findByBusiness(outlet)).stream()
            .filter(i -> i.name().equals("Burger"))
            .count();
    assertThat(burgerNamedCount).isEqualTo(1);
  }

  @Test
  void autoLinkReusesAnExistingSameNamedActivePcsIngredientCaseInsensitively() {
    UUID outlet = UUID.fromString("ee500002-2222-2222-2222-222222222222");
    // The owner already tracks "Aqua" as a whole-piece (pcs) resale ingredient with stock 5.
    UUID existing =
        asA(
            () ->
                ingredientService
                    .create(new CreateIngredientRequest(outlet, "Aqua", "pcs", null, null, null, 5))
                    .id());
    // The menu item name differs only in CASE — the V31 name unique is on lower(name), so the
    // probe MUST match it (review C1: a case-sensitive miss would collide on mint and 500).
    UUID item = createItem(outlet, "AQUA");

    asA(() -> recipeService.autoLinkAll(outlet));

    RecipeResponse recipe = asA(() -> recipeService.getRecipe(item));
    assertThat(recipe.lines()).hasSize(1);
    assertThat(recipe.lines().get(0).ingredientId())
        .as("reuses the existing pcs ingredient, never duplicates")
        .isEqualTo(existing);
    assertThat(ingredientNamed(outlet, "Aqua").stockQty()).isEqualTo(5); // stock untouched
  }

  @Test
  void autoLinkBlocksAnItemWhoseNameCollidesWithANonPcsIngredient() {
    UUID outlet = UUID.fromString("ee500005-5555-5555-5555-555555555555");
    // "Ayam" is a raw material tracked in GRAMS with stock 2000 — reusing it 1/portion would
    // silently deplete a gram of chicken per sale (review W2). Auto-link must BLOCK, not corrupt.
    asA(
        () ->
            ingredientService.create(
                new CreateIngredientRequest(outlet, "Ayam", "g", null, null, null, 2000)));
    UUID okItem = createItem(outlet, "Nasi Uduk");
    createItem(outlet, "Ayam"); // collides with the gram-tracked raw material

    AutoLinkResult result = asA(() -> recipeService.autoLinkAll(outlet));
    // Nasi Uduk links; Ayam is blocked (not linked, not minted). The sweep never crashes.
    assertThat(result.linkedCount()).isEqualTo(1);
    assertThat(result.blockedCount()).isEqualTo(1);
    assertThat(asA(() -> recipeService.getRecipe(okItem)).lines()).hasSize(1);
    // The raw "Ayam" was NOT touched (still grams, stock 2000).
    assertThat(ingredientNamed(outlet, "Ayam").unit()).isEqualTo("g");
    assertThat(ingredientNamed(outlet, "Ayam").stockQty()).isEqualTo(2000);

    // The 1-klik path surfaces the block as a domain fault (→ 422), never a silent no-op.
    UUID ayamItem =
        asA(() -> menuService.findActiveByBusiness(outlet)).stream()
            .filter(i -> i.name().equals("Ayam"))
            .findFirst()
            .orElseThrow()
            .id();
    assertThatThrownBy(() -> asA(() -> recipeService.autoLinkItem(ayamItem)))
        .isInstanceOf(RecipeAutoLinkBlockedException.class);
  }

  @Test
  void sellingALinkedItemDepletesStockAndAccumulatesDailyUsage() {
    UUID outlet = UUID.fromString("ee500003-3333-3333-3333-333333333333");
    UUID burger = createItem(outlet, "Burger Spesial");
    asA(() -> recipeService.autoLinkItem(burger));
    UUID ingredientId = ingredientNamed(outlet, "Burger Spesial").id();
    asA(() -> ingredientService.setStock(ingredientId, 10));

    // Sale #1 — 3 burgers through the REAL checkout path.
    asA(
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet,
                    "autolink-1-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(burger, 3)),
                    new PaymentRequest(TenderType.CASH, 500_000L))));

    // The stock figure (the opname sheet's PREFILL) moved: 10 − 3 = 7.
    assertThat(ingredientNamed(outlet, "Burger Spesial").stockQty()).isEqualTo(7);
    // …and "terpakai hari ini" recorded 3 for the outlet-local day.
    LocalDate today = LocalDate.now(USAGE_ZONE);
    List<IngredientUsageResponse> usage = asA(() -> ingredientService.usageForDate(outlet, today));
    assertThat(usage)
        .extracting(IngredientUsageResponse::ingredientId, IngredientUsageResponse::qtyUsed)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(ingredientId, 3L));

    // Sale #2 same day — usage ACCUMULATES (3 + 2 = 5), stock keeps falling (7 − 2 = 5).
    asA(
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet,
                    "autolink-2-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(burger, 2)),
                    new PaymentRequest(TenderType.CASH, 500_000L))));
    assertThat(ingredientNamed(outlet, "Burger Spesial").stockQty()).isEqualTo(5);
    assertThat(asA(() -> ingredientService.usageForDate(outlet, today)))
        .extracting(IngredientUsageResponse::qtyUsed)
        .containsExactly(5L);

    // A day with no sales reads empty (absence = 0 client-side).
    assertThat(asA(() -> ingredientService.usageForDate(outlet, today.minusDays(1)))).isEmpty();

    // Tenant isolation: tenant B sees NO usage for tenant A's outlet (RLS).
    List<IngredientUsageResponse> asB;
    try {
      asB =
          TenantContext.callAs(
              TENANT_B, ACTOR, () -> ingredientService.usageForDate(outlet, today));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(asB).isEmpty();
  }

  @Test
  void createItemWithAutoTrackStockLinksImmediately() {
    UUID outlet = UUID.fromString("ee500004-4444-4444-4444-444444444444");
    UUID item =
        asA(
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            outlet, "Nasi Goreng", "FOOD", 25_000L, "IDR", null, null, true))
                    .id());

    RecipeResponse recipe = asA(() -> recipeService.getRecipe(item));
    assertThat(recipe.lines()).hasSize(1);
    assertThat(recipe.lines().get(0).ingredientName()).isEqualTo("Nasi Goreng");
    assertThat(ingredientNamed(outlet, "Nasi Goreng").unit()).isEqualTo("pcs");
  }
}
