package id.co.nativeapp.barbershop.catalog.repository;

import id.co.nativeapp.barbershop.catalog.domain.ServiceItem;
import id.co.nativeapp.barbershop.catalog.projection.CatalogItemView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ServiceItem}. Carries no manual {@code WHERE company_id}: every
 * method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the RLS policy restricts results to the bound company (rule
 * 5). Read paths use a native query + projection (never {@code SELECT *} of the entity); the write
 * path (create/patch) uses the inherited {@code findById}/{@code save}.
 */
public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {

  /**
   * Lists services, optionally filtered by {@code businessId} and/or restricted to active-only,
   * ordered for display (display_order, then name). A {@code null} {@code businessId} disables that
   * filter; {@code activeOnly=false} disables the active filter.
   */
  @Query(
      value =
          """
          SELECT id AS id, business_id AS business_id, name AS name, description AS description,
                 price_minor AS price_minor, currency AS currency, active AS active,
                 display_order AS display_order, duration_minutes AS duration_minutes
            FROM service_item
           WHERE (CAST(:businessId AS uuid) IS NULL OR business_id = CAST(:businessId AS uuid))
             AND (:activeOnly = FALSE OR active = TRUE)
           ORDER BY display_order, name
          """,
      nativeQuery = true)
  List<CatalogItemView> findViews(
      @Param("businessId") UUID businessId, @Param("activeOnly") boolean activeOnly);

  /**
   * Batch-loads services by id, projected — used by the ticket checkout item-resolution path.
   * Callers chunk the id list to at most 1 000 per call (CLAUDE.md IN-clause convention).
   */
  @Query(
      value =
          """
          SELECT id AS id, business_id AS business_id, name AS name, description AS description,
                 price_minor AS price_minor, currency AS currency, active AS active,
                 display_order AS display_order, duration_minutes AS duration_minutes
            FROM service_item
           WHERE id IN (:ids)
          """,
      nativeQuery = true)
  List<CatalogItemView> findViewsByIds(@Param("ids") List<UUID> ids);
}
