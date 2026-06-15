package id.co.nativeapp.finance.group;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link GroupMember} read model.
 *
 * <p>Carries <em>no</em> manual {@code WHERE company_id = ...}: RLS scopes every read/write to the
 * bound lead company (rule 5). The consumer binds the tenant from the owning group's {@code
 * lead_company_id} (resolved from the local {@code group_ref}, never a sync call) before upserting.
 */
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

  /**
   * The member-set row for a {@code (group, member)} pair, if any. The consumer upserts on this: an
   * {@code ADDED} re-opens or creates it, a {@code REMOVED} closes it (idempotent mirror of the
   * event's post-change window).
   */
  Optional<GroupMember> findByGroupIdAndMemberCompanyId(UUID groupId, UUID memberCompanyId);
}
