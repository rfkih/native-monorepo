package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.dto.OrgUnitUserResponse;
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
 * RLS isolation for the users-per-unit read: tenant A's unit id, inside tenant B's scope, is
 * indistinguishable from an unknown id (the not-found fault — anti-enumeration). The reader carries
 * no {@code WHERE company_id}; auto-applied RLS alone scopes it (rule 5).
 */
@SpringBootTest
class OrgUnitUsersRlsIsolationTest extends PostgresRlsTestBase {

  private static final String USER_A = "aaaa1111-0000-0000-0000-00000000000a";

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentWriter assignmentWriter;
  @Autowired private OrgUnitUsersReader orgUnitUsersReader;

  @Test
  void aUnitAndItsAssignmentsAreInvisibleToAnotherTenant() throws Exception {
    var a =
        companyService.createCompany(
            new CreateCompanyCommand("RlsHubA", "IDR", "id", "A HQ", "restaurant", "a"));
    UUID rootA = a.firstBusiness().getId();
    UUID outletA =
        TenantContext.callAs(
            a.company().getId().toString(),
            "a",
            () -> orgUnitService.listActiveOutlets().get(0).id());
    TenantContext.runAs(
        a.company().getId().toString(),
        "a",
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletA)));

    var b =
        companyService.createCompany(
            new CreateCompanyCommand("RlsHubB", "USD", "en", "B HQ", "restaurant", "b"));

    // Inside B's scope, A's root unit resolves as NOT FOUND — anti-enumeration.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    b.company().getId().toString(),
                    "b",
                    () -> orgUnitUsersReader.usersForUnit(rootA)))
        .isInstanceOf(OrgUnitNotFoundException.class);

    // Sanity: inside A's own scope the assignment is visible.
    List<OrgUnitUserResponse> visibleToA =
        TenantContext.callAs(
            a.company().getId().toString(), "a", () -> orgUnitUsersReader.usersForUnit(rootA));
    assertThat(visibleToA).hasSize(1);
    assertThat(visibleToA.get(0).userId()).isEqualTo(USER_A);
  }
}
