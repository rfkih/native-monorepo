package id.co.nativeapp.org.company.repository;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link OrgUnit}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link RlsAutoApplyAspect} sets {@code app.current_tenant}
 * automatically and the PostgreSQL RLS policy restricts results to the bound company (rule 5).
 * {@code findAll} therefore returns only the bound tenant's org units, which is the read path the
 * cross-tenant isolation test relies on.
 *
 * <p><strong>Two distinct call paths:</strong>
 *
 * <ul>
 *   <li><em>Write path</em> — {@link
 *       id.co.nativeapp.org.company.service.OrgUnitWriter#cascadeDeactivate} calls the inherited
 *       {@code findAll()} because it mutates the returned entities ({@code deactivate()}) and then
 *       saves them; the full aggregate is required. Inherited JpaRepository CRUD, left alone.
 *   <li><em>Read path</em> — {@code findOrgUnitsForCurrentTenant} only maps the result to an {@link
 *       id.co.nativeapp.org.company.dto.OrgUnitResponse} DTO; it uses {@link #findAllViews()},
 *       which selects only the needed columns into an {@link OrgUnitView} projection (CLAUDE.md
 *       native-query + projection convention, rule 3.3).
 * </ul>
 *
 * <p>Native queries run on the same RLS-scoped connection as all other JPA operations — projecting
 * columns by hand does NOT weaken tenant isolation.
 */
public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

  /**
   * Every org unit visible to the bound tenant, projected to {@link OrgUnitView} and ordered
   * deterministically. No {@code WHERE company_id} — the result set is constrained solely by the
   * auto-applied RLS policy (rule 5). Used on the pure read path ({@code
   * findOrgUnitsForCurrentTenant}); the entity is never loaded, never mutated, never saved.
   *
   * <p>The write-path {@code findAll()} (used in {@code cascadeDeactivate}) is kept as the
   * inherited JpaRepository CRUD because the returned entities are mutated and saved.
   */
  @Query(
      value =
          """
          SELECT ou.id                AS id,
                 ou.name              AS name,
                 ou.type              AS type,
                 ou.parent_id         AS parent_id,
                 ou.legal_employer_id AS legal_employer_id,
                 ou.company_id        AS company_id,
                 ou.active            AS active,
                 ou.effective_from    AS effective_from,
                 ou.effective_to      AS effective_to
            FROM org_unit ou
           ORDER BY ou.effective_from, ou.id
          """,
      nativeQuery = true)
  List<OrgUnitView> findAllViews();
}
