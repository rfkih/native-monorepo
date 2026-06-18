package id.co.nativeapp.org.group.repository;

import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.projection.ConsolidationGroupView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link ConsolidationGroup}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link RlsAutoApplyAspect} sets {@code app.current_tenant}
 * automatically and the PostgreSQL RLS policy restricts results to the bound lead company (rule 5).
 * A {@code findById} therefore only ever returns a group whose lead is the bound tenant — a member
 * company querying its own scope sees nothing.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> {@link #findAllViews()} fetches
 * only the four columns a list-groups read needs ({@link ConsolidationGroupView}) rather than
 * {@code SELECT *} of the full {@code Auditable} row. The entity is loaded only on the write path
 * (the inherited {@code findById}/{@code save}, which needs the whole aggregate for mutation or
 * outbox stamping).
 *
 * <p>Native queries run on the same RLS-scoped connection — projecting columns by hand does NOT
 * weaken tenant isolation.
 */
public interface ConsolidationGroupRepository extends JpaRepository<ConsolidationGroup, UUID> {

  /**
   * Every consolidation group visible to the bound tenant, projected to {@link
   * ConsolidationGroupView} and ordered deterministically. No {@code WHERE company_id} — the result
   * set is constrained solely by the auto-applied RLS policy (rule 5). Used on pure read paths
   * where the entity is only inspected, never mutated or saved.
   */
  @Query(
      value =
          """
          SELECT cg.id                 AS id,
                 cg.lead_company_id    AS lead_company_id,
                 cg.name               AS name,
                 cg.reporting_currency AS reporting_currency
            FROM consolidation_group cg
           ORDER BY cg.name, cg.id
          """,
      nativeQuery = true)
  List<ConsolidationGroupView> findAllViews();
}
