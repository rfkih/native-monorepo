package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (d) — cross-tenant isolation for the FULL org tree, via auto-applied RLS.
 *
 * <p>Company A builds a multi-level tree (outlet + team under its root business unit). Inside
 * company B's scope, NONE of A's org units are visible — the read path ({@link
 * CompanyService#findOrgUnitsForCurrentTenant()}) carries no {@code WHERE company_id} and relies
 * solely on {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} setting the tenant GUC (rule 5). Runs
 * as the unprivileged {@code app_user} (see {@link PostgresRlsTestBase}); {@code FORCE ROW LEVEL
 * SECURITY} binds even the owning role.
 */
@SpringBootTest
class OrgUnitCrossTenantIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;

  @Test
  void aDeepOrgTreeBuiltUnderTenantAIsInvisibleToTenantB() throws Exception {
    UUID companyA =
        companyService
            .createCompany(new CreateCompanyCommand("Alpha", "IDR", "id", "A HQ", "a"))
            .company()
            .getId();
    UUID rootA =
        TenantContext.callAs(
            companyA.toString(),
            "a",
            () ->
                companyService.findOrgUnitsForCurrentTenant().stream()
                    .filter(u -> "BUSINESS_UNIT".equals(u.getType()))
                    .findFirst()
                    .orElseThrow()
                    .getId());

    // A builds outlet > team under its root business unit.
    List<UUID> aUnitIds =
        TenantContext.callAs(
            companyA.toString(),
            "a",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(new CreateOrgUnitCommand("Outlet", "outlet", rootA));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outlet.getId()));
              return List.of(rootA, outlet.getId(), team.getId());
            });

    UUID companyB =
        companyService
            .createCompany(new CreateCompanyCommand("Beta", "USD", "en", "B HQ", "b"))
            .company()
            .getId();

    // Inside B's scope: B sees only its own root business unit + its seeded default outlet
    // (ADR 0012), none of A's tree.
    List<UUID> visibleToB =
        TenantContext.callAs(
            companyB.toString(),
            "b",
            () ->
                companyService.findOrgUnitsForCurrentTenant().stream()
                    .map(OrgUnitView::getId)
                    .toList());
    assertThat(visibleToB).hasSize(2);
    assertThat(visibleToB).doesNotContainAnyElementsOf(aUnitIds);
  }
}
