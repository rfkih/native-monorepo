package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.recipe.projection.RecipeDepletionRow;
import id.co.nativeapp.restaurant.recipe.repository.RecipeLineRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-sale ingredient depletion (ADR 0050 phase A) — the recipe twin of {@link
 * id.co.nativeapp.restaurant.menu.service.StockDeductionWriter}, deliberately a SEPARATE writer:
 * that one throws on shortfall (the 86 gate) while this one floors at 0 and must NEVER throw for a
 * stock condition or block a sale — the dish was made, the money is real, and the next ingredient
 * stocktake re-establishes truth. Only genuine infrastructure failures propagate (rolling back the
 * whole sale — atomicity over availability).
 *
 * <p>Called with propagation {@code MANDATORY} from every path that records a sale (checkout,
 * payParked, bill checks, digital capture, offline replay — ONE behavior, no variants), in the same
 * transaction as the sale + outbox rows.
 *
 * <p>Algorithm: one query loads every recipe row for the sold items ({@link
 * RecipeLineRepository#findDepletionRows}); per sold line, each ingredient's net per-portion usage
 * = {@code max(0, base + Σ selected-option deltas)} (a "remove X" delta can never restock);
 * multiplied by line qty and aggregated per ingredient; applied via single-row {@code
 * GREATEST(stock_qty - qty, 0)} UPDATEs in ascending ingredient-UUID order — two concurrent sales
 * sharing two ingredients always lock in the same order, so they cannot deadlock.
 *
 * <p>Phase C reads {@code unitCostMinor}/{@code costCurrency} off the same rows to fold COGS — that
 * is why the projection already carries them.
 */
@Component
public class IngredientDepletionWriter {

  /** One sold line, minimally: the menu item, how many, and which modifier options were chosen. */
  public record DepletionLine(UUID menuItemId, int qty, List<UUID> modifierOptionIds) {}

  private final RecipeLineRepository recipeLineRepository;
  private final IngredientRepository ingredientRepository;

  public IngredientDepletionWriter(
      RecipeLineRepository recipeLineRepository, IngredientRepository ingredientRepository) {
    this.recipeLineRepository = recipeLineRepository;
    this.ingredientRepository = ingredientRepository;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void depleteForLines(List<DepletionLine> lines) {
    if (lines.isEmpty()) {
      return;
    }
    List<UUID> itemIds = lines.stream().map(DepletionLine::menuItemId).distinct().toList();
    List<RecipeDepletionRow> rows = new ArrayList<>();
    for (int i = 0; i < itemIds.size(); i += 1000) { // IN-clause chunking convention (≤ 1000)
      rows.addAll(
          recipeLineRepository.findDepletionRows(
              itemIds.subList(i, Math.min(i + 1000, itemIds.size()))));
    }
    if (rows.isEmpty()) {
      return; // none of the sold items has a recipe
    }

    // menuItemId → (base usage per ingredient, per-option deltas per ingredient)
    Map<UUID, Map<UUID, Integer>> baseByItem = new HashMap<>();
    Map<UUID, Map<UUID, Map<UUID, Integer>>> optionDeltasByItem = new HashMap<>();
    for (RecipeDepletionRow row : rows) {
      if (row.getModifierOptionId() == null) {
        baseByItem
            .computeIfAbsent(row.getMenuItemId(), id -> new HashMap<>())
            .merge(row.getIngredientId(), row.getQtyPerPortion(), Integer::sum);
      } else {
        optionDeltasByItem
            .computeIfAbsent(row.getMenuItemId(), id -> new HashMap<>())
            .computeIfAbsent(row.getModifierOptionId(), id -> new HashMap<>())
            .merge(row.getIngredientId(), row.getQtyPerPortion(), Integer::sum);
      }
    }

    // TreeMap = ascending-UUID iteration for a deterministic lock order across concurrent sales.
    TreeMap<UUID, Long> totalByIngredient = new TreeMap<>();
    for (DepletionLine line : lines) {
      Map<UUID, Integer> net = new HashMap<>(baseByItem.getOrDefault(line.menuItemId(), Map.of()));
      Map<UUID, Map<UUID, Integer>> optionDeltas =
          optionDeltasByItem.getOrDefault(line.menuItemId(), Map.of());
      if (line.modifierOptionIds() != null) {
        for (UUID optionId : line.modifierOptionIds()) {
          Map<UUID, Integer> deltas = optionDeltas.get(optionId);
          if (deltas != null) {
            deltas.forEach((ingredientId, delta) -> net.merge(ingredientId, delta, Integer::sum));
          }
        }
      }
      for (Map.Entry<UUID, Integer> entry : net.entrySet()) {
        long perPortion = Math.max(0, entry.getValue());
        if (perPortion > 0) {
          totalByIngredient.merge(entry.getKey(), perPortion * line.qty(), Long::sum);
        }
      }
    }

    for (Map.Entry<UUID, Long> entry : totalByIngredient.entrySet()) {
      int qty = (int) Math.min(entry.getValue(), Integer.MAX_VALUE);
      if (qty > 0) {
        // 0 rows = the ingredient was hard-deleted concurrently — nothing to deplete, not an error.
        ingredientRepository.depleteStockFloorZero(entry.getKey(), qty);
      }
    }
  }
}
