package id.co.nativeapp.restaurant.recipe.repository;

import id.co.nativeapp.restaurant.recipe.domain.RecipeLine;
import id.co.nativeapp.restaurant.recipe.projection.ItemHppView;
import id.co.nativeapp.restaurant.recipe.projection.RecipeDepletionRow;
import id.co.nativeapp.restaurant.recipe.projection.RecipeLineView;
import id.co.nativeapp.restaurant.recipe.projection.UnlinkedMenuItemView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RecipeLine} (ADR 0050 phase A).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * V34 RLS policy applies the tenant scope (rule 5). Reads return narrow projections via native
 * queries; the write path is delete-then-insert through the inherited {@code saveAll} (full-replace
 * PUT semantics — see {@code RecipeWriter}).
 */
public interface RecipeLineRepository extends JpaRepository<RecipeLine, UUID> {

  /** One menu item's full recipe (base + option deltas), ingredient display fields joined. */
  @Query(
      value =
          """
          SELECT rl.id                 AS id,
                 rl.ingredient_id      AS ingredient_id,
                 i.name                AS ingredient_name,
                 i.unit                AS unit,
                 rl.modifier_option_id AS modifier_option_id,
                 rl.qty_per_portion    AS qty_per_portion,
                 i.unit_cost_minor     AS unit_cost_minor,
                 i.cost_currency       AS cost_currency,
                 i.active              AS ingredient_active
            FROM recipe_line rl
            JOIN ingredient i ON i.id = rl.ingredient_id
           WHERE rl.menu_item_id = :menuItemId
           ORDER BY rl.modifier_option_id NULLS FIRST, i.name, rl.id
          """,
      nativeQuery = true)
  List<RecipeLineView> findViewsByMenuItemId(@Param("menuItemId") UUID menuItemId);

  /**
   * Every recipe line for the given menu items, joined to ingredient costs — the ONE depletion/COGS
   * query (see {@code IngredientDepletionWriter}). Callers chunk the id list at ≤ 1000.
   */
  @Query(
      value =
          """
          SELECT rl.menu_item_id       AS menu_item_id,
                 rl.ingredient_id      AS ingredient_id,
                 rl.modifier_option_id AS modifier_option_id,
                 rl.qty_per_portion    AS qty_per_portion,
                 i.unit_cost_minor     AS unit_cost_minor,
                 i.cost_currency       AS cost_currency
            FROM recipe_line rl
            JOIN ingredient i ON i.id = rl.ingredient_id
           WHERE rl.menu_item_id IN (:menuItemIds)
          """,
      nativeQuery = true)
  List<RecipeDepletionRow> findDepletionRows(@Param("menuItemIds") Collection<UUID> menuItemIds);

  /**
   * BASE lines only for an outlet's whole menu — folds into the HPP summary (option deltas out).
   */
  @Query(
      value =
          """
          SELECT rl.menu_item_id   AS menu_item_id,
                 rl.qty_per_portion AS qty_per_portion,
                 i.unit_cost_minor AS unit_cost_minor,
                 i.cost_currency   AS cost_currency
            FROM recipe_line rl
            JOIN ingredient i ON i.id = rl.ingredient_id
           WHERE rl.business_id = :businessId
             AND rl.modifier_option_id IS NULL
          """,
      nativeQuery = true)
  List<ItemHppView> findHppViewsByBusinessId(@Param("businessId") UUID businessId);

  /**
   * Rescales every recipe line that consumes an ingredient by {@code factor} — the recipe half of a
   * unit conversion.
   *
   * <p>This is the part that must not be forgotten. A pack of sauce converted to 1000 g whose
   * recipes still say "1" would deplete one GRAM per portion instead of one pack, quietly
   * under-consuming by three orders of magnitude — and the error would surface only as an
   * inexplicable surplus at the next opname.
   *
   * <p>Signed by design: a modifier-option delta may be negative, and multiplying preserves its
   * sign. Bounded to one ingredient's lines and run inside the conversion's transaction.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE recipe_line
             SET qty_per_portion = qty_per_portion * :factor,
                 updated_at      = now(),
                 updated_by      = :actor,
                 version         = version + 1
           WHERE ingredient_id = :ingredientId
          """,
      nativeQuery = true)
  int rescaleForUnitConversion(
      @Param("ingredientId") UUID ingredientId,
      @Param("factor") int factor,
      @Param("actor") String actor);

  /** Full-replace support: clears the item's recipe before inserting the new line set. */
  @Modifying
  @Query(value = "DELETE FROM recipe_line WHERE menu_item_id = :menuItemId", nativeQuery = true)
  int deleteByMenuItemId(@Param("menuItemId") UUID menuItemId);

  /**
   * Cascade hook for modifier hard-deletes ({@code ModifierWriter#deleteOption}/{@code
   * deleteGroup}): removes the per-option deltas whose option rows are being deleted, in the SAME
   * transaction — without it a deleted option would leave orphaned deltas that silently skew HPP
   * and depletion. Callers chunk at ≤ 1000.
   */
  @Modifying
  @Query(
      value = "DELETE FROM recipe_line WHERE modifier_option_id IN (:optionIds)",
      nativeQuery = true)
  int deleteByModifierOptionIds(@Param("optionIds") Collection<UUID> optionIds);

  /** Deactivation guard: is this ingredient referenced by ANY recipe line? */
  @Query(
      value = "SELECT EXISTS(SELECT 1 FROM recipe_line rl WHERE rl.ingredient_id = :ingredientId)",
      nativeQuery = true)
  boolean existsByIngredientId(@Param("ingredientId") UUID ingredientId);

  /** Auto-link probe ("Lacak stok"): does this menu item already have a recipe? */
  @Query(
      value = "SELECT EXISTS(SELECT 1 FROM recipe_line rl WHERE rl.menu_item_id = :menuItemId)",
      nativeQuery = true)
  boolean existsByMenuItemId(@Param("menuItemId") UUID menuItemId);

  /**
   * The auto-link sweep's work list: the outlet's ACTIVE menu items with NO recipe lines — each
   * gets a same-named 1:1 ingredient so its sales start depleting stock ("Lacak stok semua menu").
   * Deterministic order so a bulk run processes names stably.
   */
  @Query(
      value =
          """
          SELECT m.id   AS id,
                 m.name AS name
            FROM menu_item m
           WHERE m.business_id = :businessId
             AND m.active = TRUE
             AND NOT EXISTS (SELECT 1 FROM recipe_line rl WHERE rl.menu_item_id = m.id)
           ORDER BY m.name, m.id
          """,
      nativeQuery = true)
  List<UnlinkedMenuItemView> findUnlinkedActiveItems(@Param("businessId") UUID businessId);

  /** Names of menu items whose recipes reference an ingredient — the 409 guard's error detail. */
  @Query(
      value =
          """
          SELECT DISTINCT m.name AS name
            FROM recipe_line rl
            JOIN menu_item m ON m.id = rl.menu_item_id
           WHERE rl.ingredient_id = :ingredientId
           ORDER BY m.name
           LIMIT 10
          """,
      nativeQuery = true)
  List<String> findReferencingItemNames(@Param("ingredientId") UUID ingredientId);
}
