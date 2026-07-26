package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.dto.GroupMembershipListResponse;
import id.co.nativeapp.org.group.service.GroupService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RLS tenant-isolation proof for {@code GET /api/v1/consolidation-groups/{groupId}/members} — the
 * {@link id.co.nativeapp.org.group.repository.GroupMembershipRepository#findMemberViews} query.
 *
 * <p>Company A (the lead) creates a group and adds a member. The {@code findMemberViews} query
 * carries <em>no</em> {@code WHERE company_id} — the result set is constrained solely by the
 * auto-applied RLS policy (rule 5). This test proves that:
 *
 * <ol>
 *   <li>A sees the member it added (the query and RLS WITH CHECK both hold).
 *   <li>B, bound as a different tenant, sees an empty result for the same {@code groupId} (RLS
 *       makes A's {@code group_membership} rows invisible to B's session GUC).
 *   <li>The controller path that delegates to {@link
 *       id.co.nativeapp.org.group.service.GroupService#findMembersForGroup} maps that empty result
 *       to a logical 404 (the service returns an empty list).
 * </ol>
 *
 * <p>Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} on {@code group_membership} binds even that owning role.
 */
@SpringBootTest
class GroupMemberIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private GroupService groupService;

  @Test
  void companyBCannotReadCompanyAsGroupMembers() throws Exception {
    // --- seed: company A creates a group and adds a member ----------------------------------

    UUID companyA =
        companyService
            .createCompany(
                new CreateCompanyCommand("MemberLeadA", "IDR", "id", "A HQ", "outlet", "owner-a"))
            .company()
            .getId();
    UUID companyB =
        companyService
            .createCompany(
                new CreateCompanyCommand("MemberB", "USD", "en", "B HQ", "outlet", "owner-b"))
            .company()
            .getId();

    // A creates the group (bound as lead).
    UUID groupId =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () ->
                groupService.createGroup(new CreateGroupCommand("A Members Group", "IDR")).getId());

    // A adds company B as a member (still bound as lead A).
    TenantContext.runAs(
        companyA.toString(),
        "owner-a",
        () -> groupService.addMember(new AddMemberCommand(groupId, companyB)));

    // --- assertion: A sees the member it added -----------------------------------------------

    List<GroupMembershipListResponse> aView =
        TenantContext.callAs(
            companyA.toString(), "owner-a", () -> groupService.findMembersForGroup(groupId));
    assertThat(aView).as("Lead A must see the member it added").isNotEmpty();
    assertThat(aView.stream().map(GroupMembershipListResponse::memberCompanyId).toList())
        .contains(companyB);

    // --- assertion: B sees EMPTY (RLS scopes group_membership to the lead's company_id) ------
    //
    // findMemberViews carries only WHERE group_id = :groupId — no WHERE company_id. RLS applies
    // the bound session GUC (B's company_id), which does not match the membership rows whose
    // company_id is A. The result is empty, mirroring the controller returning 404.

    List<GroupMembershipListResponse> bView =
        TenantContext.callAs(
            companyB.toString(), "owner-b", () -> groupService.findMembersForGroup(groupId));
    assertThat(bView)
        .as(
            "Tenant B must see EMPTY members list for a group led by A"
                + " (RLS blocks A's group_membership rows from B's session GUC)")
        .isEmpty();
  }
}
