package id.co.nativeapp.carwash.catalog.repository;

import id.co.nativeapp.carwash.catalog.domain.StaffProfile;
import id.co.nativeapp.carwash.catalog.projection.StaffProfileView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StaffProfile}. Carries no manual {@code WHERE company_id}:
 * every method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically (rule 5). Read paths use a native query + projection; the write
 * path uses the inherited {@code findById}/{@code save}.
 */
public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

  /**
   * Lists staff profiles, optionally filtered by {@code businessId} and/or restricted to
   * active-only, ordered by display label.
   */
  @Query(
      value =
          """
          SELECT id AS id, business_id AS business_id, display_label AS display_label,
                 employee_id AS employee_id, active AS active
            FROM staff_profile
           WHERE (CAST(:businessId AS uuid) IS NULL OR business_id = CAST(:businessId AS uuid))
             AND (:activeOnly = FALSE OR active = TRUE)
           ORDER BY display_label
          """,
      nativeQuery = true)
  List<StaffProfileView> findViews(
      @Param("businessId") UUID businessId, @Param("activeOnly") boolean activeOnly);
}
