package id.co.nativeapp.employee.org.repository;

import id.co.nativeapp.employee.org.domain.OrgUnitProjection;
import id.co.nativeapp.employee.org.projection.OrgUnitView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link OrgUnitProjection} local org read model.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). The assignment service looks an org unit up by id
 * <em>within</em> the bound tenant to resolve its legal employer; RLS makes a cross-tenant org unit
 * invisible (empty), so the invariant cannot be satisfied with another tenant's org unit.
 */
public interface OrgUnitProjectionRepository extends JpaRepository<OrgUnitProjection, UUID> {

  /**
   * The console lookup rows for a set of org-unit ids — a projection read (only the columns the
   * console needs), never the full entity (CODE-STRUCTURE §3.3). No {@code WHERE company_id} — RLS
   * silently drops ids the bound tenant cannot see (rule 5).
   */
  @Query(
      value =
          """
          SELECT op.org_unit_id       AS org_unit_id,
                 op.legal_employer_id AS legal_employer_id,
                 op.type              AS type,
                 op.active            AS active
            FROM org_unit_projection op
           WHERE op.org_unit_id IN (:ids)
           ORDER BY op.org_unit_id
          """,
      nativeQuery = true)
  List<OrgUnitView> findViewsByIds(@Param("ids") List<UUID> ids);
}
