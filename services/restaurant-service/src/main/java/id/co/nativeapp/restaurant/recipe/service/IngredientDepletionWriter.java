package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.inventory.domain.StockLedgerDay;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStockDayRepository;
import id.co.nativeapp.restaurant.recipe.projection.RecipeDepletionRow;
import id.co.nativeapp.restaurant.recipe.repository.RecipeLineRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.lang.Nullable;
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
 * <p><strong>ADR 0067 Phase C.</strong> {@link #depleteForLines} folds COGS from the SAME depletion
 * computation — no second query. As each ingredient's floored depletion quantity is applied, its
 * {@code unitCostMinor}/{@code costCurrency} (read off the same {@link RecipeDepletionRow}
 * projection rows depletion already loaded) contributes {@code qty × unitCostMinor} to a
 * per-currency accumulator; an uncosted ingredient (null cost) contributes nothing (it cannot be
 * costed). The target currency is the lexicographically smallest one actually present among costed
 * ingredients — the same deterministic pick {@code RecipeReader}'s HPP fold uses; a currency
 * mismatch across a sale's depleted ingredients is out of scope (rejected upstream at ingredient
 * cost entry) and, like HPP, contributions in a non-target currency are simply dropped rather than
 * summed incorrectly. The fold returns {@code null} — "nothing to record" — whenever no depleted
 * ingredient carries a cost or the total folds to zero, mirroring {@code sale.cogs_minor}/{@code
 * cogs_currency}'s NULLABLE, both-or-neither invariant (V44): a sale with no costed depletion
 * leaves COGS unset, exactly like a sale with no recipe at all.
 */
@Component
public class IngredientDepletionWriter {

  /** One sold line, minimally: the menu item, how many, and which modifier options were chosen. */
  public record DepletionLine(UUID menuItemId, int qty, List<UUID> modifierOptionIds) {}

  /**
   * The Σ (depleted qty × moving-average unit cost) fold from a {@link #depleteForLines} call (ADR
   * 0067 Phase C) — the exact figure persisted to {@code sale.cogs_minor}/{@code cogs_currency} and
   * folded into the {@code SaleCogsRecorded} outbox event. {@code cogsMinor} is always strictly
   * positive (a caller never sees a zero/negative result — {@link #depleteForLines} returns {@code
   * null} instead).
   */
  public record CogsResult(long cogsMinor, String currency) {}

  private final RecipeLineRepository recipeLineRepository;
  private final IngredientRepository ingredientRepository;
  private final IngredientStockDayRepository usageRepository;

  public IngredientDepletionWriter(
      RecipeLineRepository recipeLineRepository,
      IngredientRepository ingredientRepository,
      IngredientStockDayRepository usageRepository) {
    this.recipeLineRepository = recipeLineRepository;
    this.ingredientRepository = ingredientRepository;
    this.usageRepository = usageRepository;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public @Nullable CogsResult depleteForLines(List<DepletionLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    List<UUID> itemIds = lines.stream().map(DepletionLine::menuItemId).distinct().toList();
    List<RecipeDepletionRow> rows = new ArrayList<>();
    for (int i = 0; i < itemIds.size(); i += 1000) { // IN-clause chunking convention (≤ 1000)
      rows.addAll(
          recipeLineRepository.findDepletionRows(
              itemIds.subList(i, Math.min(i + 1000, itemIds.size()))));
    }
    if (rows.isEmpty()) {
      return null; // none of the sold items has a recipe
    }

    // ingredientId → its cost pair (Phase C) — identical across every row for that ingredient (the
    // ingredient join contributes the same unitCostMinor/costCurrency regardless of which recipe
    // line/modifier-option row carried it), so the first row seen for an ingredient is
    // authoritative.
    Map<UUID, RecipeDepletionRow> costByIngredient = new HashMap<>();
    for (RecipeDepletionRow row : rows) {
      costByIngredient.putIfAbsent(row.getIngredientId(), row);
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

    TenantContext.Tenant tenant = TenantContext.require();
    String actor = tenant.actor();
    String companyId = tenant.companyId();
    LocalDate usageDate = StockLedgerDay.today();
    // ADR 0067 Phase C: per-currency COGS accumulator, folded from the SAME depletion loop (no
    // second query). TreeMap = deterministic (lexicographically) smallest-currency-first fold, the
    // RecipeReader HPP-fold precedent.
    TreeMap<String, Long> cogsByCurrency = new TreeMap<>();
    for (Map.Entry<UUID, Long> entry : totalByIngredient.entrySet()) {
      int qty = (int) Math.min(entry.getValue(), Integer.MAX_VALUE);
      if (qty > 0) {
        // 0 rows = the ingredient was hard-deleted concurrently — nothing to deplete, not an error.
        ingredientRepository.depleteStockFloorZero(entry.getKey(), qty);
        // "Terpakai hari itu" (V42, now the V47 daily ledger): record the RECIPE quantity consumed,
        // per day, in the SAME transaction — a rolled-back sale leaves no usage. Recorded as
        // usage reports what the sales consumed by recipe; the stock figure floors separately.
        // The UPSERT runs per-ingredient, right after that ingredient's deplete, so both writes
        // hold the SAME ascending-UUID lock order the depletion relies on. A single multi-row
        // batch UPSERT is DELIBERATELY avoided: Postgres would pick its own row-lock order and
        // could reintroduce the cross-sale deadlock this per-ingredient interleaving prevents.
        usageRepository.addUsage(entry.getKey(), usageDate, qty, actor, companyId);

        // Phase C: fold this ingredient's cost contribution — the SAME qty just depleted, times its
        // moving-average unit cost (ADR 0056) read off the depletion query's projection rows. An
        // uncosted ingredient (null unitCostMinor/costCurrency) contributes nothing — it cannot be
        // costed (mirrors RecipeReader's HPP fold).
        RecipeDepletionRow costRow = costByIngredient.get(entry.getKey());
        if (costRow != null
            && costRow.getUnitCostMinor() != null
            && costRow.getCostCurrency() != null) {
          long contribution = Math.multiplyExact((long) qty, costRow.getUnitCostMinor());
          cogsByCurrency.merge(costRow.getCostCurrency(), contribution, Math::addExact);
        }
      }
    }
    return foldCogs(cogsByCurrency);
  }

  /**
   * Picks the lexicographically smallest currency actually present (the {@code RecipeReader} HPP
   * fold's deterministic tie-break) and returns its accumulated total — {@code null} when nothing
   * was costed, or the target currency's total folds to zero.
   */
  private static @Nullable CogsResult foldCogs(TreeMap<String, Long> cogsByCurrency) {
    if (cogsByCurrency.isEmpty()) {
      return null;
    }
    Map.Entry<String, Long> target = cogsByCurrency.firstEntry();
    return target.getValue() > 0 ? new CogsResult(target.getValue(), target.getKey()) : null;
  }
}
