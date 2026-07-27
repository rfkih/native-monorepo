package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (c) — the cross-tenant isolation proof, relying on AUTO-applied RLS.
 *
 * <p>A company and its org_unit created under tenant A are invisible within tenant B's scope. The
 * read paths ({@link CompanyService#findCompaniesForCurrentTenant()} / {@link
 * CompanyService#findOrgUnitsForCurrentTenant()}) carry NO {@code WHERE company_id} and never call
 * the synchronizer by hand — only {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets the tenant
 * GUC on each {@code @Transactional} unit of work. This models a developer who forgets the tenant
 * and proves they are still protected (rule 5).
 *
 * <p>Each company is created via the real bootstrap flow (no inbound tenant scope), then reads are
 * issued inside each company's own scope. Runs as the unprivileged {@code app_user} role (see
 * {@link PostgresRlsTestBase}); {@code FORCE ROW LEVEL SECURITY} in the baseline binds even that
 * owning role.
 */
@SpringBootTest
class CrossTenantIsolationTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;

  @Test
  void aCompanyAndOrgUnitCreatedUnderTenantAAreInvisibleToTenantB() throws Exception {
    // Bootstrap company A and company B, each creating its own tenant.
    UUID companyA =
        companyService
            .createCompany(new CreateCompanyCommand("Alpha", "IDR", "id", "A Outlet", "owner-a"))
            .company()
            .getId();
    UUID companyB =
        companyService
            .createCompany(new CreateCompanyCommand("Beta", "USD", "en", "B Outlet", "owner-b"))
            .company()
            .getId();

    // Inside A's scope: only A's company + org_unit are visible. No WHERE clause; RLS only.
    List<UUID> companiesVisibleToA =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () ->
                companyService.findCompaniesForCurrentTenant().stream()
                    .map(CompanyView::getId)
                    .toList());
    assertThat(companiesVisibleToA).containsExactly(companyA);
    assertThat(companiesVisibleToA).doesNotContain(companyB);

    List<UUID> orgUnitsVisibleToA =
        TenantContext.callAs(
            companyA.toString(),
            "owner-a",
            () ->
                companyService.findOrgUnitsForCurrentTenant().stream()
                    .map(OrgUnitView::getId)
                    .toList());
    // A sees exactly its two org units (the first business + its seeded default outlet,
    // ADR 0012); none of B's.
    assertThat(orgUnitsVisibleToA).hasSize(2);

    UUID aOrgUnitId = orgUnitsVisibleToA.getFirst();

    // The inverse: inside B's scope only B's company + org_unit are visible.
    List<UUID> companiesVisibleToB =
        TenantContext.callAs(
            companyB.toString(),
            "owner-b",
            () ->
                companyService.findCompaniesForCurrentTenant().stream()
                    .map(CompanyView::getId)
                    .toList());
    assertThat(companiesVisibleToB).containsExactly(companyB);
    assertThat(companiesVisibleToB).doesNotContain(companyA);

    List<UUID> orgUnitsVisibleToB =
        TenantContext.callAs(
            companyB.toString(),
            "owner-b",
            () ->
                companyService.findOrgUnitsForCurrentTenant().stream()
                    .map(OrgUnitView::getId)
                    .toList());
    assertThat(orgUnitsVisibleToB).hasSize(2);
    assertThat(orgUnitsVisibleToB).doesNotContain(aOrgUnitId);
  }
}
