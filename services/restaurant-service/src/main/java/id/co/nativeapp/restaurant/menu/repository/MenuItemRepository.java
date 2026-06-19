package id.co.nativeapp.restaurant.menu.repository;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link MenuItem}.
 *
 * <p>Carries <em>no</em> manual {@code WHERE company_id} and no call to apply the tenant GUC:
 * {@link RlsAutoApplyAspect} sets {@code app.current_tenant} on the connection automatically for
 * every {@code @Transactional} method, and the PostgreSQL RLS policy restricts results to the bound
 * company (rule 5). Read paths use a native query + projection (never {@code SELECT *}).
 */
public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

  /**
   * Active items for a business, projected to {@link MenuItemView}. RLS-scoped — no manual {@code
   * WHERE company_id}. Returns only the business columns the response needs.
   */
  @Query(
      value =
          """
          SELECT m.id           AS id,
                 m.business_id  AS business_id,
                 m.name         AS name,
                 m.category     AS category,
                 m.price_minor  AS price_minor,
                 m.currency     AS currency,
                 m.active       AS active
            FROM menu_item m
           WHERE m.business_id = :businessId
             AND m.active = TRUE
           ORDER BY m.category, m.name, m.id
          """,
      nativeQuery = true)
  List<MenuItemView> findActiveByBusiness(@Param("businessId") UUID businessId);

  /**
   * Loads items by id for the checkout validation path, projected to {@link MenuItemView} —
   * checkout only needs each item's business, name, price, currency, and active flag to validate
   * the request and snapshot the line, never the full aggregate. RLS-scoped; callers chunk the id
   * list to at most 1 000 per call (CLAUDE.md IN-clause convention).
   */
  @Query(
      value =
          """
          SELECT m.id           AS id,
                 m.business_id  AS business_id,
                 m.name         AS name,
                 m.category     AS category,
                 m.price_minor  AS price_minor,
                 m.currency     AS currency,
                 m.active       AS active
            FROM menu_item m
           WHERE m.id IN (:ids)
          """,
      nativeQuery = true)
  List<MenuItemView> findViewsByIds(@Param("ids") List<UUID> ids);
}
