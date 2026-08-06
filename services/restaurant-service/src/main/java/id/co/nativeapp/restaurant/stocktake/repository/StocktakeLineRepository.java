package id.co.nativeapp.restaurant.stocktake.repository;

import id.co.nativeapp.restaurant.stocktake.domain.StocktakeLine;
import id.co.nativeapp.restaurant.stocktake.projection.StocktakeLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StocktakeLine} (ADR 0038 phase 3).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * RLS policy applies the tenant scope on both {@code stocktake_line} and the joined {@code
 * menu_item} (rule 5). Read paths return the narrow {@link StocktakeLineView} projection, joined to
 * {@code menu_item} for the display name — never {@code SELECT *}.
 */
public interface StocktakeLineRepository extends JpaRepository<StocktakeLine, UUID> {

  // Interface fields are implicitly public static final — the shared projection column list.
  String VIEW_COLUMNS =
      """
      SELECT l.id                   AS id,
             l.stocktake_id         AS stocktake_id,
             l.menu_item_id         AS menu_item_id,
             m.name                 AS name,
             l.system_qty           AS system_qty,
             l.counted_qty          AS counted_qty,
             l.variance_qty         AS variance_qty,
             l.unit_cost_minor      AS unit_cost_minor,
             l.variance_value_minor AS variance_value_minor
        FROM stocktake_line l
        JOIN menu_item m ON m.id = l.menu_item_id
      """;

  /** The lines for one stocktake, ordered by item name. */
  @Query(
      value = VIEW_COLUMNS + " WHERE l.stocktake_id = :stocktakeId ORDER BY m.name, l.id",
      nativeQuery = true)
  List<StocktakeLineView> findViewsByStocktakeId(@Param("stocktakeId") UUID stocktakeId);

  /**
   * Batch load of lines for several stocktakes at once (the history read's N+1 avoidance — the
   * {@code MenuReader} batch-load pattern). Callers chunk the id list to at most 1 000 per call
   * (CLAUDE.md IN-clause convention); history is capped at 50 so a single call always suffices.
   */
  @Query(
      value = VIEW_COLUMNS + " WHERE l.stocktake_id IN (:stocktakeIds) ORDER BY m.name, l.id",
      nativeQuery = true)
  List<StocktakeLineView> findViewsByStocktakeIds(@Param("stocktakeIds") List<UUID> stocktakeIds);
}
