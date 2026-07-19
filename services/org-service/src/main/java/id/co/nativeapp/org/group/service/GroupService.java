package id.co.nativeapp.org.group.service;

import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.AddMemberResult;
import id.co.nativeapp.org.group.dto.ConsolidationGroupListResponse;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.dto.GroupMembershipListResponse;
import id.co.nativeapp.org.group.dto.RemoveMemberCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Defines consolidation groups and manages their membership for the bound (lead) company — the P3d
 * SEAM 1 group model. It orchestrates the transactional units of work in {@link GroupWriter}; the
 * transaction boundary, RLS GUC, and outbox write all live on the writer so the Spring proxy + the
 * auto-RLS aspect engage (the {@code *Writer} pattern, ENGINEERING-STANDARDS §2.5).
 *
 * <p>All three operations are tenant-scoped (the tenant is bound at the request edge), so this
 * service only asserts a tenant is present and delegates; the group's {@code lead_company_id} /
 * {@code company_id} is stamped by the writer from {@link TenantContext}, never from the request
 * (rule 5). This is what makes the group <em>lead-owned</em>: a member company can never define or
 * mutate a group it does not lead.
 */
@Service
public class GroupService {

  private final GroupWriter writer;
  private final GroupReader reader;

  public GroupService(GroupWriter writer, GroupReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Creates a consolidation group led by the bound tenant (immutable reporting currency) and emits
   * {@code GroupDefined}.
   *
   * @param command the create command
   * @return the persisted group
   */
  public ConsolidationGroup createGroup(CreateGroupCommand command) {
    TenantContext.require();
    return writer.createGroup(command);
  }

  /**
   * Adds a member company to a group the bound tenant leads and emits {@code
   * GroupMembershipChanged(ADDED)} on a real transition.
   *
   * @param command the add-member command
   * @return the add-member outcome (the active membership and whether it was newly created)
   */
  public AddMemberResult addMember(AddMemberCommand command) {
    TenantContext.require();
    return writer.addMember(command);
  }

  /**
   * Removes a member company from a group the bound tenant leads — closing its effective window —
   * and emits {@code GroupMembershipChanged(REMOVED)} on a real transition.
   *
   * @param command the remove-member command
   * @return the closed membership, or {@code null} if there was nothing active to close
   */
  public GroupMembership removeMember(RemoveMemberCommand command) {
    TenantContext.require();
    return writer.removeMember(command);
  }

  /**
   * All consolidation groups the bound tenant leads, as a flat list. No {@code WHERE company_id} —
   * RLS scopes the result (rule 5). Projection-to-DTO mapping happens here in the service layer
   * (projection is not exposed to the controller — CODE-STRUCTURE §3.3).
   *
   * @return the list of consolidation groups for the current lead tenant
   */
  public List<ConsolidationGroupListResponse> findAllGroupsForCurrentTenant() {
    TenantContext.require();
    return reader.findAllGroupsForCurrentTenant().stream()
        .map(
            v ->
                new ConsolidationGroupListResponse(
                    v.getId(), v.getName(), v.getLeadCompanyId(), v.getReportingCurrency().strip()))
        .toList();
  }

  /**
   * All memberships for a group the bound tenant leads. RLS automatically scopes to the bound
   * lead; if the group is not visible (the caller does not lead it, or it does not exist), this
   * returns an empty list — the controller maps an empty result to {@code 404}.
   * Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
   *
   * @param groupId the consolidation group to list members for
   * @return the membership DTOs for the group
   */
  public List<GroupMembershipListResponse> findMembersForGroup(UUID groupId) {
    TenantContext.require();
    return reader.findMembersForGroup(groupId).stream()
        .map(
            v ->
                new GroupMembershipListResponse(
                    v.getMemberCompanyId(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo(),
                    GroupMembership.OPEN_ENDED.equals(v.getEffectiveTo())))
        .toList();
  }
}
