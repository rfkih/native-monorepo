package id.co.nativeapp.org.group;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GroupMembership}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the RLS policy restricts results to the bound lead company
 * (rule 5). The derived queries below are still implicitly tenant-scoped by RLS — a member company
 * can never read another lead's memberships.
 */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

  /**
   * The currently-active (open-window) membership for a company in a group, if any. Used to make
   * add-member idempotent (an already-active member is not re-added) and remove-member find the row
   * whose window to close. {@code effective_to} of the open-ended sentinel marks the active row.
   */
  Optional<GroupMembership> findByGroupIdAndMemberCompanyIdAndEffectiveTo(
      UUID groupId, UUID memberCompanyId, java.time.LocalDate effectiveTo);
}
