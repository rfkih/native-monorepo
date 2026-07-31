package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.projection.OrderView;
import id.co.nativeapp.restaurant.order.projection.ParkedOrderView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
                 COALESCE(t.label, o.table_label)   AS table_label,
                 COUNT(l.id)                       AS line_count,
                 o.occurred_at                     AS occurred_at,
                 o.order_type                      AS order_type,
                 o.source                          AS source
            FROM restaurant_order o
            LEFT JOIN restaurant_table t ON t.id = o.table_id
            LEFT JOIN order_line       l ON l.order_id = o.id
           WHERE o.business_id = :businessId
             AND o.status = 'PARKED'
           GROUP BY o.id, o.business_id, o.total_minor, o.currency,
                    t.label, o.table_label, o.occurred_at, o.order_type, o.source
           ORDER BY o.occurred_at DESC
          """,
      nativeQuery = true)
  List<ParkedOrderView> findParkedViewsByBusinessId(@Param("businessId") UUID businessId);

  /**
   * Phase 6 (ADR 0029): how many UNCONFIRMED ({@code PARKED}, {@code source=SELF_ORDER}) rows this
   * outlet currently has — the 429 cap check {@code OrderWriter.parkSelfOrder} runs before every
   * self-order create. RLS-scoped automatically (rule 5); {@code count} returning a scalar is not a
   * projection (CODE-STRUCTURE §3.3).
   */
  @Query(
      value =
          """
          SELECT COUNT(*)
            FROM restaurant_order
           WHERE business_id = :businessId
             AND status = 'PARKED'
             AND source = 'SELF_ORDER'
          """,
      nativeQuery = true)
  long countUnconfirmedSelfOrder(@Param("businessId") UUID businessId);

  /**
   * Phase 6 (ADR 0029): the lazy sweep — moves every stale {@code PARKED SELF_ORDER} row for this
   * outlet older than {@code cutoff} to the terminal, non-money-moving {@code EXPIRED} status.
   * {@code OrderWriter.parkSelfOrder} runs this before checking the unconfirmed-cap / inserting the
   * new row, so a company that never revisits an outlet's ParkedTray does not permanently lose the
   * ability to self-order there. {@code updated_by} is intentionally left unchanged (mirrors {@code
   * CouponRepository.redeemIfAvailable} — a raw {@code @Modifying} update bypasses the JPA auditing
   * listener, and this system sweep has no meaningful actor to stamp).
   *
   * @return the number of rows expired (0 is the common case — nothing was stale)
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE restaurant_order
             SET status     = 'EXPIRED',
                 updated_at = NOW(),
                 version    = version + 1
           WHERE business_id  = :businessId
             AND status       = 'PARKED'
             AND source       = 'SELF_ORDER'
             AND occurred_at  < :cutoff
          """,
      nativeQuery = true)
  int expireStaleSelfOrderParked(
      @Param("businessId") UUID businessId, @Param("cutoff") Instant cutoff);
}
