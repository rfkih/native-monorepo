package id.co.nativeapp.org.company.repository;

import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.projection.CompanyCurrentView;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link Company}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...} and no call to apply
 * the tenant GUC: every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets
 * {@code app.current_tenant} on the connection automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (correctness by default — rule 5). A company is its own
 * tenant, so a {@code findById} only ever returns the company whose id is the bound tenant.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> {@link #findAllViews()} fetches
 * only the business columns a {@link id.co.nativeapp.org.company.dto.CompanyResponse} needs ({@link
 * CompanyView}) rather than {@code SELECT *} of the full {@code Auditable} row — narrower I/O, and
 * the entity is loaded only on the write path (the inherited {@code save}/{@code saveAndFlush},
 * which legitimately needs the whole aggregate).
 *
 * <p>Native queries run on the same RLS-scoped connection as all other JPA operations — projecting
 * columns by hand does NOT weaken tenant isolation (rule 5).
 */
public interface CompanyRepository extends JpaRepository<Company, UUID> {

  /**
   * Every company visible to the bound tenant, projected to {@link CompanyView} and ordered
   * deterministically. No {@code WHERE company_id} — the result set is constrained solely by the
   * auto-applied RLS policy (rule 5). Used on the pure read path ({@code
   * findCompaniesForCurrentTenant}); the entity is never loaded, never mutated, never saved.
   */
  @Query(
      value =
          """
          SELECT c.id                AS id,
                 c.name              AS name,
                 c.base_currency     AS base_currency,
                 c.default_language  AS default_language,
                 c.legal_employer_id AS legal_employer_id,
                 c.plan_tier         AS plan_tier
            FROM company c
           ORDER BY c.name, c.id
          """,
      nativeQuery = true)
  List<CompanyView> findAllViews();

  /**
   * The single company visible to the bound tenant, projected to {@link CompanyCurrentView} —
   * includes the {@code first_business_id}: the id of the earliest-created {@code BUSINESS_UNIT}
   * org unit with {@code parent_id IS NULL}, matching the selection the create-company flow
   * inserts. RLS constrains both the {@code company} and the joined {@code org_unit} to the bound
   * tenant; no manual {@code WHERE company_id} is needed or written (rule 5). Returns {@link
   * Optional#empty()} when no company exists for the bound tenant (e.g. the tenant GUC is set but
   * the row has not been created yet).
   *
   * <p>The subquery selects the single first business by ordering all root {@code BUSINESS_UNIT}
   * nodes by {@code created_at} ascending and taking the first, replicating the implicit ordering
   * of the create-company bootstrap insert (which creates the first business in the same
   * transaction, immediately after the company row).
   */
  @Query(
      value =
          """
          SELECT c.id                AS id,
                 c.name              AS name,
                 c.base_currency     AS base_currency,
                 c.default_language  AS default_language,
                 c.legal_employer_id AS legal_employer_id,
                 c.plan_tier         AS plan_tier,
                 fb.id               AS first_business_id
            FROM company c
            JOIN (
                   SELECT ou.id
                     FROM org_unit ou
                    WHERE ou.type      = 'BUSINESS_UNIT'
                      AND ou.parent_id IS NULL
                    ORDER BY ou.created_at ASC
                    LIMIT 1
                 ) fb ON true
          """,
      nativeQuery = true)
  Optional<CompanyCurrentView> findCurrentView();
}
