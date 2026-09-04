package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.projection.IngredientView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
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
      SELECT i.id                AS id,
             i.business_id       AS business_id,
             i.name              AS name,
             i.unit              AS unit,
             i.display_unit      AS display_unit,
             i.stock_qty         AS stock_qty,
             i.unit_cost_minor   AS unit_cost_minor,
             i.cost_currency     AS cost_currency,
             i.stock_value_minor AS stock_value_minor,
             i.active            AS active
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
   * <p><strong>Moving-average value (V36).</strong> The value bucket scales DOWN with quantity so a
   * sale never moves the derived unit cost ({@code value / qty} is preserved) — REQUIRED, else
   * value stays flat while qty falls and the next receive blends against an inflated average.
   * Depleting to or past the current stock books ALL remaining value out (value -> 0), matching
   * stock -> 0. Both SET expressions read the pre-update {@code stock_qty}, so they stay
   * consistent; the value subtraction can never exceed the current value (its numerator qty is
   * capped by the CASE), so the V36 {@code ck_ingredient_stock_value_nonneg} + {@code
   * ck_ingredient_value_requires_stock} CHECKs hold. {@code unit_cost_minor} is deliberately left
   * untouched — proportional scaling keeps the average unchanged. (PostgreSQL {@code round()} is
   * HALF_UP vs the aggregate's HALF_EVEN — a sub-minor-unit difference on the value bucket only;
   * the DERIVED average is unaffected because qty and value scale together. Note a physical opname
   * does NOT reset this bucket — {@code setStock} likewise scales it proportionally — so the
   * average's residual rounding drift is only cleared by a full stockout, value -> 0, or a manual
   * revalue.)
   *
   * @return 1 if the row exists (even when already at 0); 0 if the ingredient no longer exists
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE ingredient
             SET stock_value_minor = CASE
                       WHEN stock_qty <= :qty THEN 0
                       ELSE stock_value_minor
                            - round(stock_value_minor::numeric * :qty / stock_qty)
                     END,
                 stock_qty  = GREATEST(stock_qty - :qty, 0),
                 updated_at = NOW(),
                 version    = version + 1
           WHERE id = :id
          """,
      nativeQuery = true)
  int depleteStockFloorZero(@Param("id") UUID id, @Param("qty") int qty);

  /**
   * The ACTIVE ingredient whose name matches (CASE-INSENSITIVELY) at the outlet — the auto-link
   * find-or-create probe ("Lacak stok"). CASE-INSENSITIVE is load-bearing: the V31 active-name
   * unique is on {@code lower(name)}, so a case-sensitive probe would miss a lowercase row and then
   * collide on mint (review C1). At most one row by that unique. Returns the whole aggregate so the
   * caller can decide reuse-vs-block by unit (a same-named non-{@code pcs} raw material must NOT be
   * depleted 1-per-portion — review W2).
   */
  Optional<Ingredient> findFirstByBusinessIdAndNameIgnoreCaseAndActiveTrue(
      UUID businessId, String name);
}
