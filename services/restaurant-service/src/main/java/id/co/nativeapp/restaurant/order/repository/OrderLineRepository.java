package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.projection.OrderLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link OrderLine}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies the tenant GUC
 * automatically (rule 5). Read paths return a narrow projection, never {@code SELECT *}.
 */
public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

  /**
   * All lines for a given order, projected to {@link OrderLineView}. RLS-scoped (the policy on
   * {@code order_line} uses the session tenant), so cross-company leakage is impossible even
   * without a manual filter.
   */
  @Query(
      value =
          """
          SELECT ol.id               AS id,
                 ol.menu_item_id     AS menu_item_id,
                 ol.name_snapshot    AS name_snapshot,
                 ol.unit_price_minor AS unit_price_minor,
                 ol.qty              AS qty,
                 ol.line_total_minor AS line_total_minor
            FROM order_line ol
           WHERE ol.order_id = :orderId
           ORDER BY ol.id
          """,
      nativeQuery = true)
  List<OrderLineView> findViewsByOrderId(@Param("orderId") UUID orderId);
}
