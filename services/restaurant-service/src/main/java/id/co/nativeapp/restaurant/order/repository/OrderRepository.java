package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.projection.OrderView;
import id.co.nativeapp.restaurant.order.projection.ParkedOrderView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Order}.
 *
 * <p>Carries no manual {@code WHERE company_id} and no RLS synchronizer calls: every method runs
 * inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code app.current_tenant}
 * automatically (rule 5). Read paths use a native query + projection (never {@code SELECT *}).
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

  /**
   * Finds an existing order by its client idempotency key within the bound tenant (RLS-scoped).
   * Used to make checkout idempotent on retry — the result is only ever read into the response.
   */
  @Query(
      value =
          """
          SELECT o.id              AS id,
                 o.business_id     AS business_id,
                 o.status          AS status,
                 o.total_minor     AS total_minor,
                 o.currency        AS currency,
                 o.sale_id         AS sale_id,
                 o.occurred_at     AS occurred_at,
                 o.idempotency_key AS idempotency_key,
                 o.order_type      AS order_type,
                 o.table_id        AS table_id
            FROM restaurant_order o
           WHERE o.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<OrderView> findViewByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  /**
   * Fetches a single order by id (RLS-scoped). Used for the GET /orders/{id} resume path — returns
   * the full order view including Phase 4 fields.
   */
  @Query(
      value =
          """
          SELECT o.id              AS id,
                 o.business_id     AS business_id,
                 o.status          AS status,
                 o.total_minor     AS total_minor,
                 o.currency        AS currency,
                 o.sale_id         AS sale_id,
                 o.occurred_at     AS occurred_at,
                 o.idempotency_key AS idempotency_key,
                 o.order_type      AS order_type,
                 o.table_id        AS table_id
            FROM restaurant_order o
           WHERE o.id = :orderId
          """,
      nativeQuery = true)
  Optional<OrderView> findViewById(@Param("orderId") UUID orderId);

  /**
   * Lists parked orders (status = PARKED) for a business, with table label and line count. Used for
   * the "parked orders tray" in the POS. RLS-scoped automatically.
   */
  @Query(
      value =
          """
          SELECT o.id                              AS id,
                 o.business_id                     AS business_id,
                 o.total_minor                     AS total_minor,
                 o.currency                        AS currency,
                 t.label                           AS table_label,
                 COUNT(l.id)                       AS line_count,
                 o.occurred_at                     AS occurred_at,
                 o.order_type                      AS order_type
            FROM restaurant_order o
            LEFT JOIN restaurant_table t ON t.id = o.table_id
            LEFT JOIN order_line       l ON l.order_id = o.id
           WHERE o.business_id = :businessId
             AND o.status = 'PARKED'
           GROUP BY o.id, o.business_id, o.total_minor, o.currency,
                    t.label, o.occurred_at, o.order_type
           ORDER BY o.occurred_at DESC
          """,
      nativeQuery = true)
  List<ParkedOrderView> findParkedViewsByBusinessId(@Param("businessId") UUID businessId);
}
