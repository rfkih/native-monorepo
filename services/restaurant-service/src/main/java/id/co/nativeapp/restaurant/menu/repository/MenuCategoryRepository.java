package id.co.nativeapp.restaurant.menu.repository;

import id.co.nativeapp.restaurant.menu.domain.MenuCategory;
import id.co.nativeapp.restaurant.menu.projection.MenuCategoryView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link MenuCategory}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} on the connection automatically (rule 5). Read paths use native query +
 * projection (never {@code SELECT *}).
 */
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, UUID> {

  /**
   * All categories for a business, ordered by display_order then name. RLS-scoped; no manual
   * company_id filter. Returns only the columns the response needs.
   */
  @Query(
      value =
          """
          SELECT mc.id            AS id,
                 mc.business_id   AS business_id,
                 mc.name          AS name,
                 mc.display_order AS display_order,
                 mc.active        AS active
            FROM menu_category mc
           WHERE mc.business_id = :businessId
           ORDER BY mc.display_order, mc.name, mc.id
          """,
      nativeQuery = true)
  List<MenuCategoryView> findAllByBusiness(@Param("businessId") UUID businessId);
}
