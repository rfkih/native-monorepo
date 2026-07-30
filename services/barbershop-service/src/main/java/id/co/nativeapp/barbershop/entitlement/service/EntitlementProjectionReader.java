package id.co.nativeapp.barbershop.entitlement.service;

import id.co.nativeapp.barbershop.entitlement.domain.EntitlementProjection;
import id.co.nativeapp.barbershop.entitlement.repository.EntitlementProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the local entitlement projection — whether the bound tenant is currently entitled to
 * a module.
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * the read carries NO {@code WHERE company_id}; the result set is constrained solely by the
 * auto-applied RLS policy (rule 5), so it reflects only the bound tenant's projection. This is the
 * DB-backed source of truth the {@code libs/entitlement-check} loader consults on a cache miss.
 */
@Service
public class EntitlementProjectionReader {

  private final EntitlementProjectionRepository repository;

  public EntitlementProjectionReader(EntitlementProjectionRepository repository) {
    this.repository = repository;
  }

  /**
   * Whether the bound tenant is currently entitled to a module per the local projection. A missing
   * projection row means "not entitled" (the company has never been granted the module here, or it
   * has been revoked) — fail closed.
   */
  @Transactional(readOnly = true)
  public boolean isEntitled(String moduleKey) {
    return repository
        .findByModuleKey(moduleKey)
        .map(EntitlementProjection::isEntitled)
        .orElse(false);
  }
}
