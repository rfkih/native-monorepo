package id.co.nativeapp.restaurant.table.repository;

import id.co.nativeapp.restaurant.table.domain.RestaurantTable;
import id.co.nativeapp.restaurant.table.projection.TableView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RestaurantTable}.
 *
 * <p>Carries no manual {@code WHERE company_id}: every method runs inside a {@code @Transactional},
 * so {@link RlsAutoApplyAspect} sets {@code app.current_tenant} automatically (rule 5). Read paths
 * use native queries + projections (never {@code SELECT *}).
 */
public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {

  /**
   * Lists all tables for a business, with per-table occupancy (an open order in status
   * PENDING/AWAITING_PAYMENT/PARKED references the table). RLS-scoped automatically.
   *
   * <p>Occupancy is computed via a correlated EXISTS sub-query over {@code restaurant_order}; both
   * tables are RLS-scoped to the bound tenant, so no cross-tenant data leaks.
   */
  @Query(
      value =
          """
          SELECT t.id            AS id,
                 t.business_id   AS business_id,
                 t.label         AS label,
                 t.capacity      AS capacity,
                 t.area          AS area,
                 t.active        AS active,
                 EXISTS (
                     SELECT 1 FROM restaurant_order o
                      WHERE o.table_id = t.id
                        AND o.status IN ('PENDING','AWAITING_PAYMENT','PARKED')
                 )               AS occupied
            FROM restaurant_table t
           WHERE t.business_id = :businessId
           ORDER BY t.label
          """,
      nativeQuery = true)
  List<TableView> findViewsByBusinessId(@Param("businessId") UUID businessId);

  /**
   * Checks for an existing table with the same label in the same business (within the current
   * tenant, RLS-scoped). Used before create to produce a friendly 400 instead of a constraint
   * violation.
   */
  @Query(
      value =
          """
          SELECT t.id          AS id,
                 t.business_id AS business_id,
                 t.label       AS label,
                 t.capacity    AS capacity,
                 t.area        AS area,
                 t.active      AS active,
                 FALSE         AS occupied
            FROM restaurant_table t
           WHERE t.business_id = :businessId
             AND t.label = :label
          """,
      nativeQuery = true)
  Optional<TableView> findViewByBusinessIdAndLabel(
      @Param("businessId") UUID businessId, @Param("label") String label);
}
