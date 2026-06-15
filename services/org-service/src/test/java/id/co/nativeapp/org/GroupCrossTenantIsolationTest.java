package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.CompanyService;
import id.co.nativeapp.org.company.CreateCompanyCommand;
import id.co.nativeapp.org.group.AddMemberCommand;
import id.co.nativeapp.org.group.ConsolidationGroup;
import id.co.nativeapp.org.group.ConsolidationGroupRepository;
import id.co.nativeapp.org.group.CreateGroupCommand;
import id.co.nativeapp.org.group.GroupService;
import id.co.nativeapp.org.group.RemoveMemberCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 1 — cross-tenant isolation: only the LEAD company administers its consolidation group; a
 * non-lead (member) company can neither ENUMERATE nor MUTATE another company's group, enforced by
 * auto-applied RLS (rule 5).
 *
 * <p>Company A (the lead) defines a group. Inside company B's own tenant scope: B cannot see A's
 * group (the RLS-scoped {@code findAll}/{@code findById} returns nothing), and B's attempt to
 * add/remove a member of A's group is rejected — the group is invisible under RLS, so the writer's
 * owned-group check throws (mapped to a 400). The reads carry no {@code WHERE company_id}; only the
 * auto-RLS aspect sets the tenant GUC. Runs as the unprivileged {@code app_user}; {@code FORCE ROW
 * LEVEL SECURITY} binds even the owning role.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(GroupCrossTenantIsolationTest.GroupReader.class)
class GroupCrossTenantIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private GroupService groupService;
  @Autowired private GroupReader groupReader;

  @Test
  void aGroupDefinedByLeadAIsInvisibleAndImmutableToCompanyB() throws Exception {
    UUID companyA = bootstrapCompany("Alpha", "IDR");
    UUID companyB = bootstrapCompany("Beta", "USD");
    UUID member = UUID.randomUUID();

    // A defines a group it leads.
    UUID groupId =
        TenantContext.callAs(
            companyA.toString(),
            "a",
            () -> groupService.createGroup(new CreateGroupCommand("A Group", "IDR")).getId());

    // Inside A's scope: A sees exactly its own group.
    List<UUID> visibleToA =
        TenantContext.callAs(companyA.toString(), "a", () -> groupReader.visibleGroupIds());
    assertThat(visibleToA).containsExactly(groupId);

    // Inside B's scope: B sees NONE of A's groups (RLS only — no WHERE company_id).
    List<UUID> visibleToB =
        TenantContext.callAs(companyB.toString(), "b", () -> groupReader.visibleGroupIds());
    assertThat(visibleToB).doesNotContain(groupId);
    assertThat(visibleToB).isEmpty();

    // B cannot ADD a member to A's group — the group is invisible under RLS, so the owned-group
    // check rejects it (mapped to a 400), not a silent cross-tenant write.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyB.toString(),
                    "b",
                    () -> groupService.addMember(new AddMemberCommand(groupId, member))))
        .isInstanceOf(IllegalArgumentException.class);

    // B cannot REMOVE a member of A's group either.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyB.toString(),
                    "b",
                    () -> groupService.removeMember(new RemoveMemberCommand(groupId, member))))
        .isInstanceOf(IllegalArgumentException.class);

    // And A's group is still intact (B's attempts wrote nothing).
    List<UUID> stillVisibleToA =
        TenantContext.callAs(companyA.toString(), "a", () -> groupReader.visibleGroupIds());
    assertThat(stillVisibleToA).containsExactly(groupId);
  }

  private UUID bootstrapCompany(String name, String baseCurrency) {
    return companyService
        .createCompany(new CreateCompanyCommand(name, baseCurrency, "id", "HQ", "outlet", "owner"))
        .company()
        .getId();
  }

  /**
   * A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the tenant GUC; the read
   * carries no {@code WHERE company_id}, so only RLS constrains the result.
   */
  @org.springframework.stereotype.Component
  public static class GroupReader {

    private final ConsolidationGroupRepository repository;

    public GroupReader(ConsolidationGroupRepository repository) {
      this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> visibleGroupIds() {
      return repository.findAll().stream().map(ConsolidationGroup::getId).toList();
    }
  }
}
