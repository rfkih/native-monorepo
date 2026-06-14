package id.co.nativeapp.entitlement.entitlement;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the entitlement feature — the bound tenant's entitlements, a module-existence check
 * against the catalog, and the point lookup of one entitlement row.
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * the tenant-scoped reads ({@link #entitlementsForCurrentTenant()}, {@link
 * #findEntitlement(String)}) carry NO {@code WHERE company_id}; the result set is constrained
 * solely by the auto-applied RLS policy (rule 5), so a caller only ever sees their own tenant's
 * entitlements. {@link #moduleExists(String)} reads the global, non-tenant-scoped {@code
 * module_catalog} (no RLS), and is also wrapped in a transaction so the repository call runs inside
 * one (with {@code open-in-view=false}).
 */
@Service
public class EntitlementReader {

  private final TenantEntitlementRepository entitlementRepository;
  private final ModuleCatalogRepository moduleCatalogRepository;

  public EntitlementReader(
      TenantEntitlementRepository entitlementRepository,
      ModuleCatalogRepository moduleCatalogRepository) {
    this.entitlementRepository = entitlementRepository;
    this.moduleCatalogRepository = moduleCatalogRepository;
  }

  /**
   * All entitlement rows visible to the bound tenant — RLS-constrained (no manual {@code WHERE
   * company_id}). This is the read backing {@code GET /api/v1/entitlements} and the cross-tenant
   * isolation proof: a row created under tenant A is invisible to tenant B.
   */
  @Transactional(readOnly = true)
  public List<TenantEntitlement> entitlementsForCurrentTenant() {
    return entitlementRepository.findAll();
  }

  /**
   * The entitlement row for a module within the bound tenant (RLS-scoped), if any. Used by the
   * revoke flow to confirm the module was ever granted.
   */
  @Transactional(readOnly = true)
  public Optional<TenantEntitlement> findEntitlement(String moduleKey) {
    return entitlementRepository.findByModuleKey(moduleKey);
  }

  /**
   * {@code true} if the module key exists in {@code module_catalog} (the platform offers it).
   * Reference data, not tenant-scoped — no RLS predicate applies.
   */
  @Transactional(readOnly = true)
  public boolean moduleExists(String moduleKey) {
    return moduleCatalogRepository.existsById(moduleKey);
  }

  /**
   * Whether the bound tenant is currently {@code ENTITLED} to a module — the DB-backed source of
   * truth the entitlement-check loader consults on a cache miss. RLS-scoped, so it reflects only
   * the bound tenant's entitlement.
   */
  @Transactional(readOnly = true)
  public boolean isEntitled(String moduleKey) {
    return entitlementRepository
        .findByModuleKey(moduleKey)
        .map(TenantEntitlement::isEntitled)
        .orElse(false);
  }
}
