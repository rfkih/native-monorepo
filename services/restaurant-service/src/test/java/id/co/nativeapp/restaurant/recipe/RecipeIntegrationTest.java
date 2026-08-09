package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.restaurant.recipe.domain.RecipeValidationException;
import id.co.nativeapp.restaurant.recipe.dto.HppSummaryRow;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse.Completeness;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse.LineResponse;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the recipe/BOM feature (ADR 0050 phase A): {@link RecipeService}'s PUT/GET
 * round-trip, full-replace semantics, the {@link RecipeWriter} validation matrix (mapped to {@link
 * RecipeValidationException}, 422 by {@code RecipeAdvice}), and {@link RecipeReader}'s HPP
 * arithmetic + completeness classification, including the outlet-wide {@code hpp-summary}.
 *
 * <p>Mirrors the {@code IngredientStocktakeIntegrationTest} idiom: drives the real
 * {@code @Service}/{@code @Component} beans through {@link TenantContext#callAs}, verifies via the
 * service's own read path (no controller/MockMvc layer — this is a service-level contract test,
 * matching the exemplar).
 */
@SpringBootTest
class RecipeIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-recipe@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER_BUSINESS_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private RecipeService recipeService;
  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;
  @Autowired private IngredientService ingredientService;

  // ---------------------------------------------------------------------------
  // Round-trip + full-replace semantics
  // ---------------------------------------------------------------------------

  @Test
  void putThenGetRoundTripsBaseAndOptionLinesWithHpp() throws Exception {
    UUID itemId = createItem("Burger");
    UUID bun = createIngredient(BUSINESS_ID, "Bun", 200L, "IDR");
    UUID cheese = createIngredient(BUSINESS_ID, "Cheese", 100L, "IDR");
    UUID groupId = createGroup(itemId);
    UUID extraCheeseOption = createOption(groupId, "Extra Cheese");

    RecipeResponse putResult =
        putRecipe(
            itemId,
            List.of(
                new RecipeLineInput(bun, null, 1),
                new RecipeLineInput(cheese, null, 10),
                new RecipeLineInput(cheese, extraCheeseOption, 5)));
    assertThat(putResult.menuItemId()).isEqualTo(itemId);
    assertThat(putResult.lines()).hasSize(3);

    RecipeResponse fetched = getRecipe(itemId);
    assertThat(fetched.lines()).hasSize(3);

    LineResponse baseCheese =
        fetched.lines().stream()
            .filter(l -> l.ingredientId().equals(cheese) && l.modifierOptionId() == null)
            .findFirst()
            .orElseThrow();
    assertThat(baseCheese.qtyPerPortion()).isEqualTo(10);
    assertThat(baseCheese.ingredientName()).isEqualTo("Cheese");
    assertThat(baseCheese.unit()).isEqualTo("g");
    assertThat(baseCheese.unitCostMinor()).isEqualTo(100L);
    assertThat(baseCheese.costCurrency()).isEqualTo("IDR");
    assertThat(baseCheese.ingredientActive()).isTrue();

    LineResponse optionLine =
        fetched.lines().stream()
            .filter(l -> l.modifierOptionId() != null)
            .findFirst()
            .orElseThrow();
    assertThat(optionLine.modifierOptionId()).isEqualTo(extraCheeseOption);
    assertThat(optionLine.ingredientId()).isEqualTo(cheese);
    assertThat(optionLine.qtyPerPortion()).isEqualTo(5);

    // HPP = base lines only: bun(1*200) + cheese(10*100) = 200 + 1000 = 1200. Option delta
    // excluded.
    assertThat(fetched.unitHppMinor()).isEqualTo(1_200L);
    assertThat(fetched.hppCurrency()).isEqualTo("IDR");
    assertThat(fetched.completeness()).isEqualTo(Completeness.COMPLETE);
  }

  @Test
  void fullReplaceRemovesAbsentLinesAndEmptyListClearsTheRecipe() throws Exception {
    UUID itemId = createItem("Rendang");
    UUID beef = createIngredient(BUSINESS_ID, "Beef", 500L, "IDR");
    UUID coconut = createIngredient(BUSINESS_ID, "Coconut Milk", 50L, "IDR");

    putRecipe(
        itemId,
        List.of(new RecipeLineInput(beef, null, 100), new RecipeLineInput(coconut, null, 50)));
    assertThat(getRecipe(itemId).lines()).hasSize(2);

    // Full replace: beef absent from the new list → deleted; coconut qty updated.
    putRecipe(itemId, List.of(new RecipeLineInput(coconut, null, 80)));
    RecipeResponse afterReplace = getRecipe(itemId);
    assertThat(afterReplace.lines()).hasSize(1);
    assertThat(afterReplace.lines().get(0).ingredientId()).isEqualTo(coconut);
    assertThat(afterReplace.lines().get(0).qtyPerPortion()).isEqualTo(80);

    // Empty list removes the recipe entirely.
    putRecipe(itemId, List.of());
    RecipeResponse cleared = getRecipe(itemId);
    assertThat(cleared.lines()).isEmpty();
    assertThat(cleared.unitHppMinor()).isNull();
    assertThat(cleared.hppCurrency()).isNull();
    assertThat(cleared.completeness()).isEqualTo(Completeness.MISSING_COST);
  }

  @Test
  void aFailedReplaceLeavesThePreviouslyPersistedRecipeUntouched() throws Exception {
    UUID itemId = createItem("Soto");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");
    UUID unknownIngredient = UUID.randomUUID();

    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 5)));

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(unknownIngredient, null, 1))))
        .isInstanceOf(RecipeValidationException.class);

    // The whole replace (delete-then-insert) runs in ONE REQUIRES_NEW transaction — a mid-loop
    // validation failure rolls back the delete too, so the original recipe survives intact.
    RecipeResponse stillOriginal = getRecipe(itemId);
    assertThat(stillOriginal.lines()).hasSize(1);
    assertThat(stillOriginal.lines().get(0).ingredientId()).isEqualTo(ingredientId);
    assertThat(stillOriginal.lines().get(0).qtyPerPortion()).isEqualTo(5);
  }

  // ---------------------------------------------------------------------------
  // Validation matrix — all violations -> RecipeValidationException (422 via RecipeAdvice)
  // ---------------------------------------------------------------------------

  @Test
  void putRejectsAnUnknownIngredient() throws Exception {
    UUID itemId = createItem("Item");
    UUID unknownIngredient = UUID.randomUUID();

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(unknownIngredient, null, 10))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("Unknown ingredient");
  }

  @Test
  void putRejectsAnInactiveIngredient() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Old Stock", 10L, "IDR");
    deactivateIngredient(ingredientId);

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 10))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("deactivated");
  }

  @Test
  void putRejectsAnIngredientFromAnotherOutlet() throws Exception {
    UUID itemId = createItem("Item");
    UUID foreignIngredient = createIngredient(OTHER_BUSINESS_ID, "Foreign", 10L, "IDR");

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(foreignIngredient, null, 10))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("different outlet");
  }

  @Test
  void putRejectsAModifierOptionNotBelongingToTheItem() throws Exception {
    UUID itemA = createItem("Item A");
    UUID itemB = createItem("Item B");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");
    UUID groupB = createGroup(itemB);
    UUID optionB = createOption(groupB, "Extra");

    assertThatThrownBy(
            () -> putRecipe(itemA, List.of(new RecipeLineInput(ingredientId, optionB, 5))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("does not belong to menu item");
  }

  @Test
  void putRejectsADuplicateBaseLinePair() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");

    assertThatThrownBy(
            () ->
                putRecipe(
                    itemId,
                    List.of(
                        new RecipeLineInput(ingredientId, null, 5),
                        new RecipeLineInput(ingredientId, null, 8))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("Duplicate recipe line");
  }

  @Test
  void putRejectsADuplicateOptionLinePair() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");
    UUID groupId = createGroup(itemId);
    UUID optionId = createOption(groupId, "Extra");

    assertThatThrownBy(
            () ->
                putRecipe(
                    itemId,
                    List.of(
                        new RecipeLineInput(ingredientId, optionId, 5),
                        new RecipeLineInput(ingredientId, optionId, -3))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("Duplicate recipe line");
  }

  @Test
  void putRejectsANonPositiveBaseQty() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");

    assertThatThrownBy(() -> putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 0))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("qtyPerPortion > 0");

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, -5))))
        .isInstanceOf(RecipeValidationException.class);
  }

  @Test
  void putRejectsAZeroOptionDelta() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");
    UUID groupId = createGroup(itemId);
    UUID optionId = createOption(groupId, "Extra");

    assertThatThrownBy(
            () -> putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, optionId, 0))))
        .isInstanceOf(RecipeValidationException.class)
        .hasMessageContaining("nonzero qty");
  }

  @Test
  void putRejectsAnUnknownMenuItemWithNoSuchElementException() throws Exception {
    UUID unknownItem = UUID.randomUUID();
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 1L, "IDR");

    assertThatThrownBy(
            () -> putRecipe(unknownItem, List.of(new RecipeLineInput(ingredientId, null, 5))))
        .isInstanceOf(NoSuchElementException.class);
  }

  // ---------------------------------------------------------------------------
  // HPP arithmetic + completeness
  // ---------------------------------------------------------------------------

  @Test
  void hppIsMissingCostWhenABaseLineHasNoCost() throws Exception {
    UUID itemId = createItem("Item");
    UUID costed = createIngredient(BUSINESS_ID, "Costed", 100L, "IDR");
    UUID uncosted = createIngredient(BUSINESS_ID, "Uncosted", null, null);

    RecipeResponse response =
        putRecipe(
            itemId,
            List.of(new RecipeLineInput(costed, null, 10), new RecipeLineInput(uncosted, null, 5)));

    assertThat(response.completeness()).isEqualTo(Completeness.MISSING_COST);
    // Only the costed line sums: 10 * 100 = 1000.
    assertThat(response.unitHppMinor()).isEqualTo(1_000L);
    assertThat(response.hppCurrency()).isEqualTo("IDR");
  }

  @Test
  void hppIsMissingCostWithNullValueWhenTheRecipeHasNoBaseLines() throws Exception {
    UUID itemId = createItem("Item");
    UUID ingredientId = createIngredient(BUSINESS_ID, "Salt", 10L, "IDR");
    UUID groupId = createGroup(itemId);
    UUID optionId = createOption(groupId, "Extra");

    // Only an option delta — no base line at all.
    RecipeResponse response =
        putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, optionId, 5)));

    assertThat(response.completeness()).isEqualTo(Completeness.MISSING_COST);
    assertThat(response.unitHppMinor()).isNull();
    assertThat(response.hppCurrency()).isNull();
  }

  @Test
  void hppFlagsCurrencyMismatchAndSumsOnlyTheLexicographicallySmallestCurrency() throws Exception {
    UUID itemId = createItem("Item");
    UUID idrIngredient = createIngredient(BUSINESS_ID, "Local", 100L, "IDR");
    UUID usdIngredient = createIngredient(BUSINESS_ID, "Imported", 5L, "USD");

    RecipeResponse response =
        putRecipe(
            itemId,
            List.of(
                new RecipeLineInput(idrIngredient, null, 10),
                new RecipeLineInput(usdIngredient, null, 2)));

    assertThat(response.completeness()).isEqualTo(Completeness.CURRENCY_MISMATCH);
    // "IDR" < "USD" lexicographically -> target currency is IDR; only that line sums (10*100=1000).
    assertThat(response.hppCurrency()).isEqualTo("IDR");
    assertThat(response.unitHppMinor()).isEqualTo(1_000L);
  }

  @Test
  void hppSummaryOnlyIncludesItemsWithABaseLineAndComputesCorrectSums() throws Exception {
    UUID recipedItem = createItem("Has Recipe");
    UUID noRecipeItem = createItem("No Recipe");
    UUID optionOnlyItem = createItem("Option Only");

    UUID ingredientId = createIngredient(BUSINESS_ID, "Flour", 50L, "IDR");
    putRecipe(recipedItem, List.of(new RecipeLineInput(ingredientId, null, 20)));

    UUID groupId = createGroup(optionOnlyItem);
    UUID optionId = createOption(groupId, "Extra");
    putRecipe(optionOnlyItem, List.of(new RecipeLineInput(ingredientId, optionId, 5)));
    // noRecipeItem is left without any recipe lines at all.

    List<HppSummaryRow> summary =
        TenantContext.callAs(TENANT, ACTOR, () -> recipeService.hppSummary(BUSINESS_ID));

    assertThat(summary).hasSize(1);
    HppSummaryRow row = summary.get(0);
    assertThat(row.menuItemId()).isEqualTo(recipedItem);
    assertThat(row.unitHppMinor()).isEqualTo(1_000L); // 20 * 50
    assertThat(row.hppCurrency()).isEqualTo("IDR");
    assertThat(row.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(summary.stream().map(HppSummaryRow::menuItemId))
        .doesNotContain(noRecipeItem, optionOnlyItem);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createItem(String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS_ID, name, "MAIN", 15_000L, "IDR"))
                .id());
  }

  private UUID createIngredient(UUID businessId, String name, Long unitCostMinor, String currency)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingredientService
                .create(
                    new CreateIngredientRequest(
                        businessId, name, "g", unitCostMinor, currency, 1000))
                .id());
  }

  private UUID createGroup(UUID itemId) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            modifierService
                .createGroup(
                    itemId,
                    new CreateModifierGroupRequest(BUSINESS_ID, "Size", "SINGLE", false, 0, 1, 0))
                .id());
  }

  private UUID createOption(UUID groupId, String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            modifierService
                .createOption(groupId, new CreateModifierOptionRequest(BUSINESS_ID, name, 0L, 0))
                .id());
  }

  private RecipeResponse putRecipe(UUID itemId, List<RecipeLineInput> lines) throws Exception {
    return TenantContext.callAs(
        TENANT, ACTOR, () -> recipeService.putRecipe(itemId, new PutRecipeRequest(lines)));
  }

  private RecipeResponse getRecipe(UUID itemId) throws Exception {
    return TenantContext.callAs(TENANT, ACTOR, () -> recipeService.getRecipe(itemId));
  }

  private void deactivateIngredient(UUID ingredientId) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          ingredientService.deactivate(ingredientId);
          return null;
        });
  }
}
