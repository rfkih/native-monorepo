package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.recipe.dto.HppSummaryRow;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse.Completeness;
import id.co.nativeapp.restaurant.recipe.projection.ItemHppView;
import id.co.nativeapp.restaurant.recipe.projection.RecipeLineView;
import id.co.nativeapp.restaurant.recipe.repository.RecipeLineRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the recipe feature (ADR 0050 phase A): assembles a menu item's recipe with its
 * derived HPP, and the outlet-wide HPP summary.
 *
 * <p><strong>HPP arithmetic (rule 8):</strong> {@code Σ (base qty × unit_cost_minor)} — integer
 * multiplication and addition only, in minor units; no division, no floats. Only BASE lines count
 * toward the headline HPP (option deltas price the customization, not the standard portion). Only
 * lines whose {@code cost_currency} equals the target currency are summed; the target is the
 * lexicographically smallest distinct cost currency present (deterministic — in practice a tenant
 * has exactly one). Completeness precedence: {@code CURRENCY_MISMATCH} &gt; {@code MISSING_COST}
 * &gt; {@code COMPLETE}.
 */
@Component
public class RecipeReader {

  private final RecipeLineRepository repository;
  private final MenuItemRepository menuItemRepository;
  private final OutletAccessGuard outletAccessGuard;

  public RecipeReader(
      RecipeLineRepository repository,
      MenuItemRepository menuItemRepository,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.menuItemRepository = menuItemRepository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * A menu item's recipe + derived HPP. Empty recipe → empty lines, null HPP, MISSING_COST.
   *
   * <p>Outlet-scoped like the ingredient-cost reads it derives from ({@code
   * IngredientReader#findByBusiness} precedent, review W1): the guard runs against the item's
   * outlet when the item exists; an unknown/cross-tenant item falls through to the empty response
   * (which carries no cost data).
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public RecipeResponse getRecipe(UUID menuItemId) {
    List<MenuItemView> itemViews = menuItemRepository.findViewsByIds(List.of(menuItemId));
    if (!itemViews.isEmpty()) {
      outletAccessGuard.enforce(itemViews.get(0).getBusinessId());
    }
    List<RecipeLineView> views = repository.findViewsByMenuItemId(menuItemId);
    List<RecipeResponse.LineResponse> lines = views.stream().map(RecipeReader::toLine).toList();

    List<CostTerm> baseTerms =
        views.stream()
            .filter(v -> v.getModifierOptionId() == null)
            .map(v -> new CostTerm(v.getQtyPerPortion(), v.getUnitCostMinor(), v.getCostCurrency()))
            .toList();
    Hpp hpp = fold(baseTerms);
    return new RecipeResponse(menuItemId, lines, hpp.unitHppMinor, hpp.currency, hpp.completeness);
  }

  /**
   * Outlet-wide HPP summary — one row per menu item that HAS at least one base recipe line.
   * Outlet-scoped (review W1): HPP is derived ingredient-cost data, so the read follows the
   * {@code IngredientReader#findByBusiness} guard precedent, not the open menu-catalog one.
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public List<HppSummaryRow> hppSummary(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    Map<UUID, List<CostTerm>> byItem = new LinkedHashMap<>();
    for (ItemHppView view : repository.findHppViewsByBusinessId(businessId)) {
      byItem
          .computeIfAbsent(view.getMenuItemId(), id -> new ArrayList<>())
          .add(
              new CostTerm(
                  view.getQtyPerPortion(), view.getUnitCostMinor(), view.getCostCurrency()));
    }
    List<HppSummaryRow> rows = new ArrayList<>(byItem.size());
    for (Map.Entry<UUID, List<CostTerm>> entry : byItem.entrySet()) {
      Hpp hpp = fold(entry.getValue());
      rows.add(new HppSummaryRow(entry.getKey(), hpp.unitHppMinor, hpp.currency, hpp.completeness));
    }
    return rows;
  }

  /**
   * The recipe-feature guard reads (used by {@code RecipeIngredientGuard} — see ADR 0050): is the
   * ingredient referenced by any recipe, and which menu items reference it (≤ 10, for the 409
   * detail). Join the caller's transaction — the deactivation runs inside one.
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public boolean isIngredientReferenced(UUID ingredientId) {
    return repository.existsByIngredientId(ingredientId);
  }

  /** See {@link #isIngredientReferenced}. */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public List<String> referencingItemNames(UUID ingredientId) {
    return repository.findReferencingItemNames(ingredientId);
  }

  /** Projection → dto mapping lives here, not on the dto (the ArchUnit layering rule). */
  private static RecipeResponse.LineResponse toLine(RecipeLineView view) {
    return new RecipeResponse.LineResponse(
        view.getId(),
        view.getIngredientId(),
        view.getIngredientName(),
        view.getUnit(),
        view.getModifierOptionId(),
        view.getQtyPerPortion(),
        view.getUnitCostMinor(),
        view.getCostCurrency(),
        view.getIngredientActive());
  }

  private record CostTerm(int qty, Long unitCostMinor, String costCurrency) {}

  private record Hpp(Long unitHppMinor, String currency, Completeness completeness) {}

  private static Hpp fold(List<CostTerm> baseTerms) {
    if (baseTerms.isEmpty()) {
      return new Hpp(null, null, Completeness.MISSING_COST);
    }
    TreeSet<String> currencies = new TreeSet<>();
    boolean anyUncosted = false;
    for (CostTerm term : baseTerms) {
      if (term.unitCostMinor() == null || term.costCurrency() == null) {
        anyUncosted = true;
      } else {
        currencies.add(term.costCurrency());
      }
    }
    if (currencies.isEmpty()) {
      // Base lines exist but none carries a cost — nothing to sum.
      return new Hpp(null, null, Completeness.MISSING_COST);
    }
    String target = currencies.first();
    long sum = 0L;
    for (CostTerm term : baseTerms) {
      if (term.unitCostMinor() != null && target.equals(term.costCurrency())) {
        sum += term.qty() * term.unitCostMinor();
      }
    }
    Completeness completeness =
        currencies.size() > 1
            ? Completeness.CURRENCY_MISMATCH
            : anyUncosted ? Completeness.MISSING_COST : Completeness.COMPLETE;
    return new Hpp(sum, target, completeness);
  }
}
