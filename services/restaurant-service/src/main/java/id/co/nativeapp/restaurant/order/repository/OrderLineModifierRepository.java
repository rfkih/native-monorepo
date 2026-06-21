package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.OrderLineModifier;
import id.co.nativeapp.restaurant.order.projection.OrderLineModifierView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link OrderLineModifier}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies RLS (rule 5). Read
 * paths use native query + projection.
 */
public interface OrderLineModifierRepository extends JpaRepository<OrderLineModifier, UUID> {

  /** All modifier snapshots for a given order line, ordered by id (insertion order). RLS-scoped. */
  @Query(
      value =
          """
          SELECT olm.id                AS id,
                 olm.option_id         AS option_id,
                 olm.name_snapshot     AS name_snapshot,
                 olm.price_delta_minor AS price_delta_minor
            FROM order_line_modifier olm
           WHERE olm.order_line_id = :orderLineId
           ORDER BY olm.id
          """,
      nativeQuery = true)
  List<OrderLineModifierView> findViewsByOrderLineId(@Param("orderLineId") UUID orderLineId);

  /**
   * All modifier snapshots for a batch of order line ids. Callers must chunk to ≤ 1 000 ids per
   * call (CLAUDE.md IN-clause convention).
   */
  @Query(
      value =
          """
          SELECT olm.id                AS id,
                 olm.order_line_id     AS order_line_id,
                 olm.option_id         AS option_id,
                 olm.name_snapshot     AS name_snapshot,
                 olm.price_delta_minor AS price_delta_minor
            FROM order_line_modifier olm
           WHERE olm.order_line_id IN (:orderLineIds)
           ORDER BY olm.order_line_id, olm.id
          """,
      nativeQuery = true)
  List<OrderLineModifierView> findViewsByOrderLineIds(
      @Param("orderLineIds") List<UUID> orderLineIds);
}
