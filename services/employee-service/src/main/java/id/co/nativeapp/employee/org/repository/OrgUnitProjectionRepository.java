package id.co.nativeapp.employee.org.repository;

import id.co.nativeapp.employee.org.domain.OrgUnitProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link OrgUnitProjection} local org read model.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). The assignment service
 * looks an org unit up by id <em>within</em> the bound tenant to resolve its legal employer; RLS
 * makes a cross-tenant org unit invisible (empty), so the invariant cannot be satisfied with
 * another tenant's org unit.
 */
public interface OrgUnitProjectionRepository extends JpaRepository<OrgUnitProjection, UUID> {}
