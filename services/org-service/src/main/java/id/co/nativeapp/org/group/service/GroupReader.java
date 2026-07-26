package id.co.nativeapp.org.group.service;

import id.co.nativeapp.org.group.projection.ConsolidationGroupView;
import id.co.nativeapp.org.group.projection.GroupMembershipView;
import id.co.nativeapp.org.group.repository.ConsolidationGroupRepository;
import id.co.nativeapp.org.group.repository.GroupMembershipRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the {@code GET /api/v1/consolidation-groups} and {@code GET
 * /api/v1/consolidation-groups/{groupId}/members} paths.
 *
 * <p>A separate {@code @Component} (not a private method on {@link GroupService}) so the read-only
 * transaction is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC, which
 * is the load-bearing mechanism that scopes every query to the bound company (rule 5).
 *
 * <p>{@code readOnly = true} signals PostgreSQL that no write will follow, allowing the driver to
 * skip acquiring a write lock and a replica to serve the read.
 */
@Component
public class GroupReader {

  private final ConsolidationGroupRepository groupRepository;
  private final GroupMembershipRepository membershipRepository;

  public GroupReader(
      ConsolidationGroupRepository groupRepository,
      GroupMembershipRepository membershipRepository) {
    this.groupRepository = groupRepository;
    this.membershipRepository = membershipRepository;
  }

  /**
   * All consolidation groups the bound tenant leads, projected to {@link ConsolidationGroupView}.
   * No {@code WHERE company_id} — RLS constrains the result set (rule 5).
   *
   * @return the flat list of groups for the current lead tenant
   */
  @Transactional(readOnly = true)
  public List<ConsolidationGroupView> findAllGroupsForCurrentTenant() {
    return groupRepository.findAllViews();
  }

  /**
   * All memberships for a group the bound tenant leads, projected to {@link GroupMembershipView}.
   * RLS automatically scopes to the bound lead; if the group is not owned by the bound tenant, the
   * repository returns an empty list (the group is invisible under RLS). The caller must interpret
   * an empty result as "no such group or no members" and return 404 / empty as appropriate.
   *
   * @param groupId the consolidation group to list members for
   * @return the membership projections for the group (empty if the group is not found under RLS)
   */
  @Transactional(readOnly = true)
  public List<GroupMembershipView> findMembersForGroup(UUID groupId) {
    return membershipRepository.findMemberViews(groupId);
  }
}
