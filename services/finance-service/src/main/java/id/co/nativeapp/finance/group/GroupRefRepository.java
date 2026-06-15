package id.co.nativeapp.finance.group;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link GroupRef} read model.
 *
 * <p>Carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data method is
 * transactional, so {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} and the RLS policy restricts results to the bound lead company (rule 5). The
 * consumer binds the tenant from the {@code GroupDefined} event's {@code lead_company_id} before
 * upserting, so a {@code findById(group_id)} only ever returns a row owned by that lead.
 */
public interface GroupRefRepository extends JpaRepository<GroupRef, UUID> {}
