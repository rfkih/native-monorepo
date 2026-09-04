package id.co.nativeapp.restaurant.order.repository;

import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.projection.ItemPopularityView;
import id.co.nativeapp.restaurant.order.projection.OrderLineView;
import id.co.nativeapp.restaurant.order.projection.SoldItemView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.Instant;
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

  /**
   * Units SOLD per menu item at a business, best-sellers first — the union of (a) lines on
   * COMPLETED walk-in orders and (b) PAID bill lines (a guest-tab line only counts once its check
   * is settled). Projected to {@link ItemPopularityView}; RLS additionally scopes both sides to the
   * session tenant (rule 5).
   */
  @Query(
      value =
          """
          SELECT t.menu_item_id AS menu_item_id,
                 SUM(t.sold_qty) AS sold_qty
            FROM (
                  SELECT ol.menu_item_id AS menu_item_id, SUM(ol.qty) AS sold_qty
                    FROM order_line ol
                    JOIN restaurant_order o ON o.id = ol.order_id
                   WHERE o.business_id = :businessId
                     AND o.status = 'COMPLETED'
                   GROUP BY ol.menu_item_id
                  UNION ALL
                  SELECT bl.menu_item_id AS menu_item_id, SUM(bl.qty) AS sold_qty
                    FROM bill_line bl
                    JOIN bill b ON b.id = bl.bill_id
                   WHERE b.business_id = :businessId
                     AND bl.paid = TRUE
                   GROUP BY bl.menu_item_id
                 ) t
           GROUP BY t.menu_item_id
           ORDER BY sold_qty DESC
          """,
      nativeQuery = true)
  List<ItemPopularityView> itemPopularityByBusinessId(@Param("businessId") UUID businessId);

  /**
   * Per-menu-item units SOLD and gross revenue over a time window {@code [from, to)} — the union of
   * (a) COMPLETED walk-in order lines and (b) PAID bill lines, each keyed to its {@code sale} so
   * the window matches the Z-report's own universe ({@code sale.occurred_at ∈ [from, to)} for the
   * business — capture time, not order-placement time). Grouped per menu item, best-sellers first;
   * {@code name} is the {@code MAX} of the line name snapshots (renamed items keep their sold-time
   * name, no {@code menu} join). Projected to {@link SoldItemView}; RLS additionally scopes every
   * table to the session tenant (rule 5).
   */
  @Query(
      value =
          """
          SELECT t.menu_item_id       AS menu_item_id,
                 MAX(t.name)          AS name,
                 SUM(t.sold_qty)      AS sold_qty,
                 SUM(t.revenue_minor) AS revenue_minor
            FROM (
                  SELECT ol.menu_item_id           AS menu_item_id,
                         MAX(ol.name_snapshot)     AS name,
                         SUM(ol.qty)               AS sold_qty,
                         SUM(ol.line_total_minor)  AS revenue_minor
                    FROM order_line ol
                    JOIN restaurant_order o ON o.id = ol.order_id
                    JOIN sale s ON s.id = o.sale_id
                   WHERE s.business_id = :businessId
                     AND o.status = 'COMPLETED'
                     AND s.occurred_at >= :from
                     AND s.occurred_at <  :to
                   GROUP BY ol.menu_item_id
                  UNION ALL
                  SELECT bl.menu_item_id           AS menu_item_id,
                         MAX(bl.name_snapshot)     AS name,
                         SUM(bl.qty)               AS sold_qty,
                         SUM(bl.line_total_minor)  AS revenue_minor
                    FROM bill_line bl
                    JOIN sale s ON s.id = bl.paid_sale_id
                   WHERE s.business_id = :businessId
                     AND bl.paid = TRUE
                     AND s.occurred_at >= :from
                     AND s.occurred_at <  :to
                   GROUP BY bl.menu_item_id
                 ) t
           GROUP BY t.menu_item_id
           ORDER BY sold_qty DESC
          """,
      nativeQuery = true)
  List<SoldItemView> soldItemsByWindow(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
