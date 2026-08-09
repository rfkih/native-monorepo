package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.projection.IngredientView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Ingredient} (ADR 0046 phase 1).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * RLS policy applies the tenant scope (rule 5). The read path returns the narrow {@link
 * IngredientView} projection via a native query; the write path uses the inherited {@code
 * save}/{@code saveAndFlush} (needs the whole aggregate).
 */
public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

  // Interface fields are implicitly public static final — the shared projection column list.
  String VIEW_COLUMNS =
      """
      SELECT i.id              AS id,
             i.business_id     AS business_id,
             i.name            AS name,
             i.unit            AS unit,
             i.stock_qty       AS stock_qty,
             i.unit_cost_minor AS unit_cost_minor,
             i.cost_currency   AS cost_currency,
             i.active          AS active
        FROM ingredient i
      """;

  /** Active ingredients for an outlet, ordered by name. */
  @Query(
      value =
          VIEW_COLUMNS
              + " WHERE i.business_id = :businessId AND i.active = TRUE ORDER BY i.name,"
              + " i.id",
      nativeQuery = true)
  List<IngredientView> findActiveByBusiness(@Param("businessId") UUID businessId);

  /**
   * Per-sale recipe depletion (ADR 0050 phase A): subtracts {@code qty} flooring at 0 — an
   * ingredient shortfall must NEVER block or roll back a sale (the dish was made; the 86 gate
   * remains {@code menu_item.stock_quantity}). The V31 {@code ck_ingredient_stock_nonneg} CHECK
   * stays intact because {@code GREATEST(stock_qty - :qty, 0)} can never go negative. Bumps {@code
   * updated_at}/{@code version} like {@code MenuItemRepository#deductStock}; the true level is
   * re-established at the next ingredient stocktake.
   *
   * @return 1 if the row exists (even when already at 0); 0 if the ingredient no longer exists
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE ingredient
             SET stock_qty  = GREATEST(stock_qty - :qty, 0),
                 updated_at = NOW(),
                 version    = version + 1
           WHERE id = :id
          """,
      nativeQuery = true)
  int depleteStockFloorZero(@Param("id") UUID id, @Param("qty") int qty);
}
