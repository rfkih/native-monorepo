package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.projection.OrderView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
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
                 o.idempotency_key AS idempotency_key
            FROM restaurant_order o
           WHERE o.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<OrderView> findViewByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
