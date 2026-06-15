package id.co.nativeapp.entitlement.entitlement.repository;

import id.co.nativeapp.entitlement.entitlement.domain.ModuleCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link ModuleCatalog} reference data.
 *
 * <p>A thin data port: the only access path is the existence check the service uses to validate a
 * {@code module_key} before granting it ({@code existsById}, inherited from {@link JpaRepository}).
 * The catalog is global reference data (not tenant-scoped), so no RLS/{@code company_id} predicate
 * applies; the table carries no {@code company_id}.
 */
public interface ModuleCatalogRepository extends JpaRepository<ModuleCatalog, String> {}
