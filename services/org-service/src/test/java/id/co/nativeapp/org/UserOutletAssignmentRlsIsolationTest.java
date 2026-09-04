package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.projection.UserOutletAssignmentView;
import id.co.nativeapp.org.user.service.UserOutletAssignmentReader;
import id.co.nativeapp.org.user.service.UserOutletAssignmentWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RLS cross-tenant isolation for {@code user_outlet_assignment}.
 *
 * <p>Company A assigns User A to its outlet. Inside company B's scope, that assignment must be
 * INVISIBLE — the reader carries no {@code WHERE company_id} and relies solely on {@link
 * id.co.nativeapp.tenant.RlsAutoApplyAspect} setting the tenant GUC (rule 5). Runs as the
 * unprivileged {@code app_user} (see {@link PostgresRlsTestBase}); {@code FORCE ROW LEVEL SECURITY}
 * binds even the owning role, so this proves the policy is meaningful.
 */
@SpringBootTest
class UserOutletAssignmentRlsIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentWriter writer;
  @Autowired private UserOutletAssignmentReader reader;

  private static final String USER_A = "kc-sub-rls-user-a";

  @Test
  void assignmentsBuiltUnderTenantAreInvisibleToADifferentTenant() throws Exception {
    // --- Tenant A: create a company, an outlet, assign User A to it ---
    UUID companyA =
        companyService
            .createCompany(new CreateCompanyCommand("RlsCompanyA", "IDR", "id", "restaurant", "a"))
            .company()
            .getId();

    UUID outletA =
        TenantContext.callAs(
            companyA.toString(),
            "a",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(new CreateOrgUnitCommand("A Outlet", "outlet", null));
              return outlet.getId();
            });

    TenantContext.runAs(
        companyA.toString(), "a", () -> writer.replaceAssignments(USER_A, List.of(outletA)));

    // --- Tenant B: create a DIFFERENT company ---
    UUID companyB =
        companyService
            .createCompany(new CreateCompanyCommand("RlsCompanyB", "USD", "en", "restaurant", "b"))
            .company()
            .getId();

    // --- Inside B's scope: User A's assignments from A are invisible ---
    List<UserOutletAssignmentView> visibleToB =
        TenantContext.callAs(companyB.toString(), "b", () -> reader.findActiveByUserId(USER_A));

    assertThat(visibleToB)
        .as("RLS must isolate company A's user assignments from company B's scope")
        .isEmpty();

    // --- Sanity: inside A's scope the assignment is visible ---
    List<UserOutletAssignmentView> visibleToA =
        TenantContext.callAs(companyA.toString(), "a", () -> reader.findActiveByUserId(USER_A));

    assertThat(visibleToA).hasSize(1);
    assertThat(visibleToA.get(0).getOrgUnitId()).isEqualTo(outletA);
  }
}
