package id.co.nativeapp.org.group;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ConsolidationGroup}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the PostgreSQL RLS policy restricts results to the bound
 * lead company (rule 5). A {@code findById} therefore only ever returns a group whose lead is the
 * bound tenant — a member company querying its own scope sees nothing.
 */
public interface ConsolidationGroupRepository extends JpaRepository<ConsolidationGroup, UUID> {}
