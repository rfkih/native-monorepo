package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.domain.IngredientInUseException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link RecipeModifierCascade} and {@link RecipeIngredientGuard} integration tests (ADR 0050 phase
 * A): modifier hard-deletes cascade-clean per-option recipe deltas in the same transaction, and
 * {@code IngredientWriter#deactivate} is vetoed while any recipe still references the ingredient.
 */
@SpringBootTest
class RecipeCascadeAndGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-recipe-cascade@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;
  @Autowired private IngredientService ingredientService;
  @Autowired private RecipeService recipeService;

  // ---------------------------------------------------------------------------
  // RecipeModifierCascade
  // ---------------------------------------------------------------------------

  @Test
  void deletingAModifierOptionRemovesItsRecipeDeltaButKeepsBaseLines() throws Exception {
    UUID itemId = createItem("Burger");
    UUID baseIngredient = createIngredient("Bun");
    UUID deltaIngredient = createIngredient("Cheese");
    UUID groupId = createGroup(itemId);
    UUID optionId = createOption(groupId, "Extra Cheese");

    putRecipe(
        itemId,
        List.of(
            new RecipeLineInput(baseIngredient, null, 1),
            new RecipeLineInput(deltaIngredient, optionId, 20)));
    assertThat(getRecipe(itemId).lines()).hasSize(2);

    deleteOption(optionId);

    RecipeResponse afterDelete = getRecipe(itemId);
    assertThat(afterDelete.lines()).hasSize(1);
    assertThat(afterDelete.lines().get(0).ingredientId()).isEqualTo(baseIngredient);
    assertThat(afterDelete.lines().get(0).modifierOptionId()).isNull();
  }

  @Test
  void deletingAWholeGroupRemovesAllItsOptionsRecipeDeltas() throws Exception {
    UUID itemId = createItem("Coffee");
    UUID baseIngredient = createIngredient("Coffee Beans");
    UUID milkIngredient = createIngredient("Milk");
    UUID sugarIngredient = createIngredient("Sugar");
    UUID groupId = createGroup(itemId);
    UUID milkOption = createOption(groupId, "Extra Milk");
    UUID sugarOption = createOption(groupId, "Extra Sugar");

    putRecipe(
        itemId,
        List.of(
            new RecipeLineInput(baseIngredient, null, 15),
            new RecipeLineInput(milkIngredient, milkOption, 30),
            new RecipeLineInput(sugarIngredient, sugarOption, 5)));
    assertThat(getRecipe(itemId).lines()).hasSize(3);

    deleteGroup(groupId);

    RecipeResponse afterDelete = getRecipe(itemId);
    assertThat(afterDelete.lines()).hasSize(1);
    assertThat(afterDelete.lines().get(0).ingredientId()).isEqualTo(baseIngredient);
    assertThat(afterDelete.lines().get(0).modifierOptionId()).isNull();
  }

  // ---------------------------------------------------------------------------
  // RecipeIngredientGuard
  // ---------------------------------------------------------------------------

  @Test
  void deactivatingARecipeReferencedIngredientThrowsAndSucceedsOnceUnreferenced() throws Exception {
    UUID itemId = createItem("Steak");
    UUID ingredientId = createIngredient("Beef");
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 200)));

    assertThatThrownBy(() -> deactivateIngredient(ingredientId))
        .isInstanceOf(IngredientInUseException.class)
        .hasMessageContaining("Steak");

    // The veto did not partially apply anything — the recipe line is still there.
    assertThat(getRecipe(itemId).lines()).hasSize(1);

    // Remove the recipe reference, then deactivation must succeed.
    putRecipe(itemId, List.of());
    assertThatCode(() -> deactivateIngredient(ingredientId)).doesNotThrowAnyException();
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

  private UUID createIngredient(String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingredientService
                .create(new CreateIngredientRequest(BUSINESS_ID, name, "g", null, 10L, "IDR", 1000))
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
                    new CreateModifierGroupRequest(BUSINESS_ID, "Options", "MULTI", false, 0, 2, 0))
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

  private void deleteOption(UUID optionId) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          modifierService.deleteOption(optionId);
          return null;
        });
  }

  private void deleteGroup(UUID groupId) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          modifierService.deleteGroup(groupId);
          return null;
        });
  }

  private void putRecipe(UUID itemId, List<RecipeLineInput> lines) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          recipeService.putRecipe(itemId, new PutRecipeRequest(lines));
          return null;
        });
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
