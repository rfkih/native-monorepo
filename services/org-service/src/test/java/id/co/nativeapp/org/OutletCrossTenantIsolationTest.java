package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.OutletResponse;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance tests for {@code GET /api/v1/outlets} (via {@link OrgUnitService#listActiveOutlets}):
 *
 * <ul>
 *   <li>Only ACTIVE OUTLET-type nodes are returned — inactive outlets and non-OUTLET types (branch,
 *       business_unit, team) are excluded.
 *   <li>Results are ordered by name.
 *   <li>RLS cross-tenant isolation: company B cannot see company A's outlets.
 * </ul>
 *
 * <p>Runs against the unprivileged {@code app_user} (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} binds even the owning role, so this is a meaningful isolation proof.
 */
@SpringBootTest
class OutletCrossTenantIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;

  /** Bootstraps a company and returns the root business-unit id. */
  private record Tenant(UUID companyId, UUID rootBusinessUnitId) {}

  private Tenant bootstrapCompany(String name, String actor) {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", "outlet", actor));
    return new Tenant(result.company().getId(), result.firstBusiness().getId());
  }

  @Test
  void listActiveOutletsReturnsOnlyActiveOutletTypeNodesOrderedByName() throws Exception {
    Tenant t = bootstrapCompany("Acme", "owner-acme");

    List<UUID> outletIds =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-acme",
            () -> {
              // branch under root business unit
              var branch =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("North Branch", "branch", t.rootBusinessUnitId()));
              // two active outlets under the branch
              var outletZ =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Zebra Outlet", "outlet", branch.getId()));
              var outletA =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Apple Outlet", "outlet", branch.getId()));
              // one inactive outlet (deactivated)
              var outletInactive =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Closed Outlet", "outlet", branch.getId()));
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outletInactive.getId(), null, false, null, true, false));
              // a team — must NOT appear even though it is active
              orgUnitService.create(new CreateOrgUnitCommand("Kitchen", "team", outletA.getId()));

              return List.of(outletA.getId(), outletZ.getId());
            });

    List<OutletResponse> outlets =
        TenantContext.callAs(
            t.companyId().toString(), "owner-acme", () -> orgUnitService.listActiveOutlets());

    // Only the 2 active outlets — inactive and non-OUTLET types excluded.
    assertThat(outlets).hasSize(2);
    // Ordered by name: "Apple Outlet" < "Zebra Outlet"
    assertThat(outlets.get(0).name()).isEqualTo("Apple Outlet");
    assertThat(outlets.get(1).name()).isEqualTo("Zebra Outlet");
    // Response shape: {id, name} only.
    assertThat(outlets.get(0).id()).isEqualTo(outletIds.get(0));
    assertThat(outlets.get(1).id()).isEqualTo(outletIds.get(1));
  }

  @Test
  void listActiveOutletsReturnsEmptyListWhenNoActiveOutletsExist() throws Exception {
    Tenant t = bootstrapCompany("EmptyCo", "owner-empty");

    // No outlets created — only the bootstrap root business unit exists.
    List<OutletResponse> outlets =
        TenantContext.callAs(
            t.companyId().toString(), "owner-empty", () -> orgUnitService.listActiveOutlets());

    assertThat(outlets).isEmpty();
  }

  @Test
  void outletsBuiltUnderTenantAAreInvisibleToTenantB() throws Exception {
    Tenant a = bootstrapCompany("TenantA", "owner-a");
    Tenant b = bootstrapCompany("TenantB", "owner-b");

    // Tenant A creates outlets.
    TenantContext.callAs(
        a.companyId().toString(),
        "owner-a",
        () -> {
          var branch =
              orgUnitService.create(
                  new CreateOrgUnitCommand("A Branch", "branch", a.rootBusinessUnitId()));
          orgUnitService.create(new CreateOrgUnitCommand("A Outlet 1", "outlet", branch.getId()));
          orgUnitService.create(new CreateOrgUnitCommand("A Outlet 2", "outlet", branch.getId()));
          return null;
        });

    // Tenant B sees NO outlets — RLS scopes the query to B's company only.
    List<OutletResponse> visibleToB =
        TenantContext.callAs(
            b.companyId().toString(), "owner-b", () -> orgUnitService.listActiveOutlets());

    assertThat(visibleToB).isEmpty();
  }
}
