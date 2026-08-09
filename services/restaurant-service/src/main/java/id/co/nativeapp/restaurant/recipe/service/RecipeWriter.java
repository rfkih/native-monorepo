package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.recipe.domain.RecipeLine;
import id.co.nativeapp.restaurant.recipe.domain.RecipeValidationException;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.repository.RecipeLineRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the full-replace recipe write (ADR 0050 phase A): the request's lines become the ENTIRE
 * recipe of the menu item — delete-then-insert in one transaction, so a concurrent depletion sees
 * either the whole old recipe or the whole new one, never a blend.
 *
 * <p>Validation (all violations → {@link RecipeValidationException}, HTTP 422):
 *
 * <ul>
 *   <li>every ingredient exists, is ACTIVE, and belongs to the menu item's outlet;
 *   <li>every non-null {@code modifierOptionId} belongs to one of the menu item's modifier groups;
 *   <li>no duplicate (ingredient, option-scope) pair in the request;
 *   <li>sign rules (base &gt; 0; option delta ≠ 0 — also enforced by the domain ctor + V34 CHECK).
 * </ul>
 *
 * <p>Distinct bean from {@link RecipeService} so the transactional method runs through the Spring
 * proxy (the {@code IngredientWriter} pattern — the RLS GUC aspect binds on proxy invocation).
 */
@Component
public class RecipeWriter {

  private final RecipeLineRepository recipeLineRepository;
  private final MenuItemRepository menuItemRepository;
  private final IngredientRepository ingredientRepository;
  private final ModifierOptionRepository modifierOptionRepository;
  private final OutletAccessGuard outletAccessGuard;

  public RecipeWriter(
      RecipeLineRepository recipeLineRepository,
      MenuItemRepository menuItemRepository,
      IngredientRepository ingredientRepository,
      ModifierOptionRepository modifierOptionRepository,
      OutletAccessGuard outletAccessGuard) {
    this.recipeLineRepository = recipeLineRepository;
    this.menuItemRepository = menuItemRepository;
    this.ingredientRepository = ingredientRepository;
    this.modifierOptionRepository = modifierOptionRepository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Replaces the menu item's recipe with the given lines (empty list = remove the recipe).
   *
   * @throws NoSuchElementException if the menu item is unknown/cross-tenant (→ 404)
   * @throws RecipeValidationException on any content violation (→ 422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replace(UUID menuItemId, PutRecipeRequest request) {
    String companyId = TenantContext.require().companyId();
    MenuItem item =
        menuItemRepository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    outletAccessGuard.enforce(item.getBusinessId());

    List<RecipeLineInput> lines = request.lines();
    validateNoDuplicatePairs(lines);
    validateIngredients(lines, item.getBusinessId());
    validateOptionOwnership(lines, menuItemId);

    recipeLineRepository.deleteByMenuItemId(menuItemId);
    List<RecipeLine> entities = new ArrayList<>(lines.size());
    for (RecipeLineInput input : lines) {
      RecipeLine line =
          new RecipeLine(
              item.getBusinessId(),
              menuItemId,
              input.ingredientId(),
              input.modifierOptionId(),
              input.qtyPerPortion());
      line.setCompanyId(companyId);
      entities.add(line);
    }
    recipeLineRepository.saveAllAndFlush(entities);
  }

  /**
   * Cascade delete for hard-deleted modifier options (called via {@code RecipeModifierCascade}
   * inside {@code ModifierWriter}'s deleting transaction — hence {@code MANDATORY}): removes the
   * per-option deltas whose option rows are being deleted, chunked ≤ 1000.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void deleteForModifierOptions(java.util.Collection<UUID> optionIds) {
    if (optionIds.isEmpty()) {
      return;
    }
    List<UUID> ids = new ArrayList<>(optionIds);
    for (int i = 0; i < ids.size(); i += 1000) { // IN-clause chunking convention (≤ 1000)
      recipeLineRepository.deleteByModifierOptionIds(
          ids.subList(i, Math.min(i + 1000, ids.size())));
    }
  }

  private static void validateNoDuplicatePairs(List<RecipeLineInput> lines) {
    Set<String> seen = new HashSet<>();
    for (RecipeLineInput line : lines) {
      String key = line.ingredientId() + "|" + Objects.toString(line.modifierOptionId(), "-");
      if (!seen.add(key)) {
        throw new RecipeValidationException(
            "Duplicate recipe line for ingredient "
                + line.ingredientId()
                + (line.modifierOptionId() != null
                    ? " and modifier option " + line.modifierOptionId()
                    : " (base line)"));
      }
    }
  }

  private void validateIngredients(List<RecipeLineInput> lines, UUID businessId) {
    Set<UUID> ingredientIds =
        lines.stream().map(RecipeLineInput::ingredientId).collect(Collectors.toSet());
    if (ingredientIds.isEmpty()) {
      return;
    }
    // Write path — loading the full (small) aggregates is the house convention for validation.
    var byId =
        ingredientRepository.findAllById(ingredientIds).stream()
            .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
    for (UUID id : ingredientIds) {
      Ingredient ingredient = byId.get(id);
      if (ingredient == null) {
        throw new RecipeValidationException("Unknown ingredient: " + id);
      }
      if (!ingredient.isActive()) {
        throw new RecipeValidationException(
            "Ingredient is deactivated: " + ingredient.getName() + " (" + id + ")");
      }
      if (!businessId.equals(ingredient.getBusinessId())) {
        throw new RecipeValidationException(
            "Ingredient belongs to a different outlet: " + ingredient.getName() + " (" + id + ")");
      }
    }
  }

  private void validateOptionOwnership(List<RecipeLineInput> lines, UUID menuItemId) {
    Set<UUID> optionIds =
        lines.stream()
            .map(RecipeLineInput::modifierOptionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (optionIds.isEmpty()) {
      return;
    }
    Set<UUID> owned = new HashSet<>(modifierOptionRepository.findIdsByMenuItemId(menuItemId));
    for (UUID optionId : optionIds) {
      if (!owned.contains(optionId)) {
        throw new RecipeValidationException(
            "Modifier option " + optionId + " does not belong to menu item " + menuItemId);
      }
    }
  }
}
