package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.dto.OrgUnitUserResponse;
import id.co.nativeapp.org.user.service.InvalidUnitUsersTargetException;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.org.user.service.OrgUnitUsersReader;
import id.co.nativeapp.org.user.service.UserOutletAssignmentWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for {@code GET /api/v1/org-units/{orgUnitId}/users} (via {@link OrgUnitUsersReader}) —
 * the org-unit hub's People tab source:
 *
 * <ul>
 *   <li>a BUSINESS_UNIT returns active assignments across ALL its child outlets (incl. the seeded
 *       default outlet), with outlet names;
 *   <li>an OUTLET returns only its own assignments;
 *   <li>a valid unit with no assignments returns an empty list (200, not 404);
 *   <li>a TEAM target is rejected (teams carry no assignments — 400 semantics);
 *   <li>an unknown id is rejected with the not-found fault (404 semantics).
 * </ul>
 */
@SpringBootTest
class OrgUnitUsersAcceptanceTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner-hub";
  private static final String USER_A = "aaaa1111-0000-0000-0000-000000000001";
  private static final String USER_B = "bbbb2222-0000-0000-0000-000000000002";

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentWriter assignmentWriter;
  @Autowired private OrgUnitUsersReader orgUnitUsersReader;

  private record Setup(UUID companyId, UUID rootId, UUID seededOutletId) {}

  private Setup bootstrap(String name) throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", "restaurant", ACTOR));
    UUID companyId = result.company().getId();
    UUID rootId = result.firstBusiness().getId();
    UUID seededOutletId =
        TenantContext.callAs(
            companyId.toString(), ACTOR, () -> orgUnitService.listActiveOutlets().get(0).id());
    return new Setup(companyId, rootId, seededOutletId);
  }

  @Test
  void aBusinessUnitReturnsAssignmentsAcrossItsChildOutlets() throws Exception {
    Setup s = bootstrap("HubAcross");
    UUID secondOutlet =
        TenantContext.callAs(
            s.companyId().toString(),
            ACTOR,
            () ->
                orgUnitService
                    .create(new CreateOrgUnitCommand("Second Outlet", "outlet", s.rootId()))
                    .getId());
    TenantContext.runAs(
        s.companyId().toString(),
        ACTOR,
        () -> {
          assignmentWriter.replaceAssignments(USER_A, List.of(s.seededOutletId()));
          assignmentWriter.replaceAssignments(USER_B, List.of(secondOutlet));
        });

    List<OrgUnitUserResponse> users =
        TenantContext.callAs(
            s.companyId().toString(), ACTOR, () -> orgUnitUsersReader.usersForUnit(s.rootId()));

    assertThat(users).hasSize(2);
    assertThat(users.stream().map(OrgUnitUserResponse::userId)).contains(USER_A, USER_B);
    assertThat(users.stream().map(OrgUnitUserResponse::outletName)).contains("Second Outlet");
  }

  @Test
  void anOutletReturnsOnlyItsOwnAssignments() throws Exception {
    Setup s = bootstrap("HubOwn");
    UUID secondOutlet =
        TenantContext.callAs(
            s.companyId().toString(),
            ACTOR,
            () ->
                orgUnitService
                    .create(new CreateOrgUnitCommand("Second Outlet", "outlet", s.rootId()))
                    .getId());
    TenantContext.runAs(
        s.companyId().toString(),
        ACTOR,
        () -> {
          assignmentWriter.replaceAssignments(USER_A, List.of(s.seededOutletId()));
          assignmentWriter.replaceAssignments(USER_B, List.of(secondOutlet));
        });

    List<OrgUnitUserResponse> users =
        TenantContext.callAs(
            s.companyId().toString(), ACTOR, () -> orgUnitUsersReader.usersForUnit(secondOutlet));

    assertThat(users).hasSize(1);
    assertThat(users.get(0).userId()).isEqualTo(USER_B);
    assertThat(users.get(0).orgUnitId()).isEqualTo(secondOutlet);
    assertThat(users.get(0).outletName()).isEqualTo("Second Outlet");
  }

  @Test
  void aValidUnitWithNoAssignmentsReturnsAnEmptyList() throws Exception {
    Setup s = bootstrap("HubEmpty");

    List<OrgUnitUserResponse> users =
        TenantContext.callAs(
            s.companyId().toString(), ACTOR, () -> orgUnitUsersReader.usersForUnit(s.rootId()));

    assertThat(users).isEmpty();
  }

  @Test
  void aClosedAssignmentIsExcluded() throws Exception {
    Setup s = bootstrap("HubClosed");
    TenantContext.runAs(
        s.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(s.seededOutletId())));
    // Replace-set with an empty list closes the assignment (active = false, kept for audit).
    TenantContext.runAs(
        s.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of()));

    List<OrgUnitUserResponse> users =
        TenantContext.callAs(
            s.companyId().toString(), ACTOR, () -> orgUnitUsersReader.usersForUnit(s.rootId()));

    assertThat(users).as("closed (inactive) assignments must not appear").isEmpty();
  }

  @Test
  void aTeamTargetIsRejected() throws Exception {
    Setup s = bootstrap("HubTeam");
    UUID teamId =
        TenantContext.callAs(
            s.companyId().toString(),
            ACTOR,
            () ->
                orgUnitService
                    .create(new CreateOrgUnitCommand("Kitchen", "team", s.seededOutletId()))
                    .getId());

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    s.companyId().toString(), ACTOR, () -> orgUnitUsersReader.usersForUnit(teamId)))
        .isInstanceOf(InvalidUnitUsersTargetException.class);
  }

  @Test
  void anUnknownUnitIsRejectedWithNotFound() throws Exception {
    Setup s = bootstrap("HubUnknown");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    s.companyId().toString(),
                    ACTOR,
                    () -> orgUnitUsersReader.usersForUnit(UUID.randomUUID())))
        .isInstanceOf(OrgUnitNotFoundException.class);
  }
}
