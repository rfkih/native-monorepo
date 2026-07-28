package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.OrgUnitListResponse;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.group.dto.ConsolidationGroupListResponse;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.service.GroupService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (e) — RLS tenant isolation for the NEW read endpoints ({@code GET /api/v1/org-units}
 * and {@code GET /api/v1/consolidation-groups}) introduced for the console dashboard pages.
 *
 * <p>Proves that company A's org units and consolidation groups are invisible within company B's
 * tenant scope, exercising auto-applied RLS through the service-layer read path ({@link
 * OrgUnitService#findAllForCurrentTenant()} and {@link
 * GroupService#findAllGroupsForCurrentTenant()} — no {@code WHERE company_id} in the queries, only
 * the auto-RLS GUC). Runs as the unprivileged {@code app_user} role (see {@link
 * PostgresRlsTestBase}); {@code FORCE ROW LEVEL SECURITY} in the baseline binds even that owning
 * role (rule 5).
 */
@SpringBootTest
class OrgUnitAndGroupReadIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private GroupService groupService;

  @Test
  void orgUnitsCreatedByCompanyAareInvisibleToCompanyB() throws Exception {
    UUID companyA =
        companyService
            .createCompany(
                new CreateCompanyCommand(
                    "IsolationA", "IDR", "id", "A Outlet", "restaurant", "owner-a"))
            .company()
            .getId();
    UUID companyB =
        companyService
            .createCompany(
                new CreateCompanyCommand(
                    "IsolationB", "USD", "en", "B Outlet", "restaurant", "owner-b"))
            .company()
            .getId();

    // A sees its own org units.
    List<UUID> aUnits =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () ->
                orgUnitService.findAllForCurrentTenant().stream()
                    .map(OrgUnitListResponse::id)
                    .toList());
    assertThat(aUnits).isNotEmpty();

    // B sees NONE of A's org units.
    List<UUID> bUnits =
        TenantContext.callAs(
            companyB.toString(),
            "owner-b",
            () ->
                orgUnitService.findAllForCurrentTenant().stream()
                    .map(OrgUnitListResponse::id)
                    .toList());
    assertThat(bUnits).doesNotContainAnyElementsOf(aUnits);
  }

  @Test
  void consolidationGroupsCreatedByCompanyAareInvisibleToCompanyB() throws Exception {
    UUID companyA =
        companyService
            .createCompany(
                new CreateCompanyCommand(
                    "GroupLeadA", "IDR", "id", "A HQ", "restaurant", "owner-a"))
            .company()
            .getId();
    UUID companyB =
        companyService
            .createCompany(
                new CreateCompanyCommand(
                    "GroupMemberB", "USD", "en", "B HQ", "restaurant", "owner-b"))
            .company()
            .getId();

    // A defines a group it leads.
    UUID groupId =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () -> groupService.createGroup(new CreateGroupCommand("A Corp Group", "IDR")).getId());

    // A sees its own group.
    List<UUID> aGroups =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () ->
                groupService.findAllGroupsForCurrentTenant().stream()
                    .map(ConsolidationGroupListResponse::id)
                    .toList());
    assertThat(aGroups).contains(groupId);

    // B sees NONE of A's groups.
    List<UUID> bGroups =
        TenantContext.callAs(
            companyB.toString(),
            "owner-b",
            () ->
                groupService.findAllGroupsForCurrentTenant().stream()
                    .map(ConsolidationGroupListResponse::id)
                    .toList());
    assertThat(bGroups).doesNotContain(groupId);
  }
}
