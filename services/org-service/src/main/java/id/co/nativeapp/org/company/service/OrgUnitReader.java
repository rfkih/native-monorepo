package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.projection.OutletView;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the {@code GET /api/v1/org-units} path.
 *
 * <p>A separate {@code @Component} (not a private method on {@link OrgUnitService}) so the
 * read-only transaction is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC, which
 * is the load-bearing mechanism that scopes the query to the bound company (rule 5).
 *
 * <p>{@code readOnly = true} signals PostgreSQL that no write will follow, allowing the driver to
 * skip acquiring a write lock and a replica to serve the read.
 */
@Component
public class OrgUnitReader {

  private final OrgUnitRepository orgUnitRepository;

  public OrgUnitReader(OrgUnitRepository orgUnitRepository) {
    this.orgUnitRepository = orgUnitRepository;
  }

  /**
   * All org units visible to the bound tenant, ordered deterministically (by {@code
   * effective_from}, then {@code id}). The frontend assembles the tree from this flat list keyed on
   * {@code id} and {@code parentId} — {@code ORDER BY effective_from, id} is a deterministic row
   * ordering, not a topological parent-before-child guarantee.
   *
   * <p>No {@code WHERE company_id} — RLS constrains the result set to the bound company (rule 5).
   * Uses the {@link OrgUnitView} native-query projection — never {@code SELECT *} of the entity
   * (CLAUDE.md §3.3).
   *
   * @return the deterministically ordered flat list of org units for the current tenant
   */
  @Transactional(readOnly = true)
  public List<OrgUnitView> findAllForCurrentTenant() {
    return orgUnitRepository.findAllViews();
  }

  /**
   * Active OUTLET nodes visible to the bound tenant, ordered by name. No {@code WHERE company_id} —
   * RLS constrains the result set to the bound company (rule 5). Used by the POS outlet picker
   * ({@code GET /api/v1/outlets}).
   *
   * <p>Uses the {@link OutletView} projection — only {@code id}, {@code name}, and the parent
   * business unit's {@code vertical} are selected (CLAUDE.md §3.3).
   *
   * @return active outlets for the current tenant, ordered by name
   */
  @Transactional(readOnly = true)
  public List<OutletView> findActiveOutletsForCurrentTenant() {
    return orgUnitRepository.findActiveOutlets();
  }
}
