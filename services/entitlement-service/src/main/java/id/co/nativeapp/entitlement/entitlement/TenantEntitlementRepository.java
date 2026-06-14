package id.co.nativeapp.entitlement.entitlement;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link TenantEntitlement} aggregate.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC on every {@code @Transactional}
 * method (rule 5). {@link #findByModuleKey(String)} is the point lookup the grant/revoke flow uses
 * to upsert the {@code (company_id, module_key)} row, and {@code findAll()} (inherited) is the
 * RLS-scoped "modules for the bound tenant" read — both implicitly confined to the bound tenant by
 * RLS, never by a hand-written predicate.
 */
public interface TenantEntitlementRepository extends JpaRepository<TenantEntitlement, UUID> {

  /**
   * The entitlement row for a module within the bound tenant (RLS-scoped), if any. There is at most
   * one per {@code (company_id, module_key)} (the unique constraint), so this is a point lookup. No
   * {@code company_id} predicate is written — RLS adds it.
   */
  Optional<TenantEntitlement> findByModuleKey(String moduleKey);
}
