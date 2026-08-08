package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktakeLine;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStocktakeLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IngredientStocktakeLine} (ADR 0046 phase 1).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * RLS policy applies the tenant scope on both {@code ingredient_stocktake_line} and the joined
 * {@code ingredient} (rule 5). Read paths return the narrow {@link IngredientStocktakeLineView}
 * projection, joined to {@code ingredient} for the display name/unit — never {@code SELECT *}.
 */
public interface IngredientStocktakeLineRepository
    extends JpaRepository<IngredientStocktakeLine, UUID> {

  // Interface fields are implicitly public static final — the shared projection column list.
  String VIEW_COLUMNS =
      """
      SELECT l.id                     AS id,
             l.ingredient_stocktake_id AS ingredient_stocktake_id,
             l.ingredient_id          AS ingredient_id,
             i.name                   AS name,
             i.unit                   AS unit,
             l.system_qty             AS system_qty,
             l.counted_qty            AS counted_qty,
             l.variance_qty           AS variance_qty,
             l.unit_cost_minor        AS unit_cost_minor,
             l.variance_value_minor   AS variance_value_minor
        FROM ingredient_stocktake_line l
        JOIN ingredient i ON i.id = l.ingredient_id
      """;

  /** The lines for one ingredient stocktake, ordered by ingredient name. */
  @Query(
      value =
          VIEW_COLUMNS + " WHERE l.ingredient_stocktake_id = :stocktakeId ORDER BY i.name, l.id",
      nativeQuery = true)
  List<IngredientStocktakeLineView> findViewsByStocktakeId(@Param("stocktakeId") UUID stocktakeId);

  /**
   * Batch load of lines for several ingredient stocktakes at once (the history read's N+1
   * avoidance). Callers chunk the id list to at most 1 000 per call (CLAUDE.md IN-clause
   * convention); history is capped at 50 so a single call always suffices.
   */
  @Query(
      value =
          VIEW_COLUMNS
              + " WHERE l.ingredient_stocktake_id IN (:stocktakeIds) ORDER BY i.name,"
              + " l.id",
      nativeQuery = true)
  List<IngredientStocktakeLineView> findViewsByStocktakeIds(
      @Param("stocktakeIds") List<UUID> stocktakeIds);
}
