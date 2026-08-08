package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.projection.IngredientView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
