package id.co.nativeapp.org.company;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link OrgUnit}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link id.co.nativeapp.org.config.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the PostgreSQL RLS policy restricts results to the bound
 * company (rule 5). {@code findAll} therefore returns only the bound tenant's org units, which is
 * the read path the cross-tenant isolation test relies on.
 */
public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {}
