package id.co.nativeapp.carwash.entitlement.repository;

import id.co.nativeapp.carwash.entitlement.domain.EntitlementProjection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link EntitlementProjection} local entitlement read model.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). The record-wash gate looks
 * a module up by key <em>within</em> the bound tenant to learn whether the company is entitled; RLS
 * makes a cross-tenant projection row invisible (empty), so a company can never be entitled via
 * another tenant's row.
 */
public interface EntitlementProjectionRepository
    extends JpaRepository<EntitlementProjection, UUID> {

  /** The projection row for a module within the bound tenant (RLS-scoped), if any. */
  Optional<EntitlementProjection> findByModuleKey(String moduleKey);
}
