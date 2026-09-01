package id.co.nativeapp.org.company.repository;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.projection.OutletView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
                 ou.vertical          AS vertical,
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

  /**
   * Active OUTLET nodes visible to the bound tenant, projected to {@link OutletView} and ordered by
   * name. No {@code WHERE company_id} — the result set is constrained solely by the auto-applied
   * RLS policy (rule 5). Used by the POS outlet picker ({@code GET /api/v1/outlets}).
   *
   * <p>Selects only the columns the picker needs ({@code id}, {@code name}) — never {@code SELECT
   * *} of the entity (CLAUDE.md §3.3). ADR 0070 removed the parent self-join this query used to
   * carry: the {@code vertical} is a COMPANY attribute now (read once from {@code
   * /api/v1/companies}) and the {@code division_id} it exposed no longer exists. <strong>The {@code
   * type = 'OUTLET'} filter STAYS.</strong> It is tempting to drop it now that an outlet is the
   * only creatable kind, but a tenant that has not yet been flattened still holds {@code
   * BUSINESS_UNIT}/{@code TEAM} rows, and the reconciler runs on {@code ApplicationReadyEvent} —
   * i.e. AFTER this endpoint is already serving. Without the filter the POS picker would offer a
   * division during that window and a cashier could ring sales against a business-unit id:
   * precisely the invariant ADR 0012 established and ADR 0070 keeps.
   */
  @Query(
      value =
          """
          SELECT ou.id   AS id,
                 ou.name AS name
            FROM org_unit ou
           WHERE ou.type = 'OUTLET'
             AND ou.active = true
           ORDER BY ou.name
          """,
      nativeQuery = true)
  List<OutletView> findActiveOutlets();

  /**
   * One org unit projected to {@link OrgUnitView} — the read-path existence/type guard (e.g. the
   * users-per-unit endpoint validates its target without loading the entity). No {@code WHERE
   * company_id} — RLS scopes the lookup, so a foreign unit resolves empty exactly like an unknown
   * one (anti-enumeration, rule 5).
   */
  @Query(
      value =
          """
          SELECT ou.id                AS id,
                 ou.name              AS name,
                 ou.type              AS type,
                 ou.vertical          AS vertical,
                 ou.parent_id         AS parent_id,
                 ou.legal_employer_id AS legal_employer_id,
                 ou.company_id        AS company_id,
                 ou.active            AS active,
                 ou.effective_from    AS effective_from,
                 ou.effective_to      AS effective_to
            FROM org_unit ou
           WHERE ou.id = :id
          """,
      nativeQuery = true)
  Optional<OrgUnitView> findViewById(@Param("id") UUID id);
}
