package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.InvalidPlanTierException;
import id.co.nativeapp.org.company.service.PlanTierForbiddenException;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Acceptance coverage for the P1 slice of the UMKM tier-mode plan (ADR 0044) — {@code
 * company.plan_tier}, its read path, and the owner-only {@code PUT
 * /api/v1/companies/current/plan-tier} setter — run under a REAL bound tenant + RLS-enforcing
 * Postgres (no mocking of {@link CompanyService} or its collaborators), mirroring {@link
 * CrossTenantIsolationTest}'s service-layer style.
 *
 * <p>The owner-only guard reads the {@code X-Roles} header via {@code
 * company.config.ActorRolesProvider}, which is bound to Spring's {@link RequestContextHolder} — the
 * same minimal-setup technique restaurant-service's {@code OutletEnforcementTest} uses: no MockMvc,
 * no HTTP server, no {@code DevTenantFilter}, just the raw request-attribute binding the provider
 * relies on.
 */
@SpringBootTest
class PlanTierAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;

  private MockHttpServletRequest mockRequest;

  @BeforeEach
  void bindMockRequest() {
    mockRequest = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  /**
   * Rebinds a FRESH request carrying the given {@code X-Roles} header ({@link
   * MockHttpServletRequest} headers are additive, so switching roles mid-test needs a new request).
   */
  private void setRoles(String roles) {
    mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private UUID bootstrapCompany(String name, String actor) throws Exception {
    return companyService
        .createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " Outlet", "restaurant", actor))
        .company()
        .getId();
  }

  // ── (a) findCurrentView returns planTier; create-path + grandfather defaults ──

  @Test
  void aFreshlyBootstrappedCompanyStartsOnTheFreeTier() throws Exception {
    // ADR 0044 D4 "signup-default-FREE", delivered with ADR 0047: every create path starts the
    // company at FREE; only PRE-EXISTING rows grandfather to FULL via the V10 column default
    // (covered by the raw-insert test below).
    UUID companyId = bootstrapCompany("Warung Acme", "owner-a");

    CompanyResponse response =
        TenantContext.callAs(companyId.toString(), "owner-a", companyService::getCurrentCompany);

    assertThat(response.planTier()).isEqualTo("FREE");
  }

  @Test
  void aPreMigrationCompanyRowWithNoPlanTierColumnValueDefaultsToFullViaTheColumnDefault()
      throws Exception {
    // Simulate a row that predates this feature: inserted directly over the admin (BYPASSRLS)
    // connection, naming every NOT NULL column EXCEPT plan_tier, so the V10 column DEFAULT
    // ('FULL') is the only thing that can have supplied the value — the grandfather guarantee
    // (D6: "existing companies default FULL — no regression"). company_code (ADR 0054) is also
    // NOT NULL but has NO column default, so it is named explicitly here.
    UUID companyId = UUID.randomUUID();
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO company (id, name, base_currency, default_language,"
                    + " legal_employer_id, company_code, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id) VALUES (?, ?, ?, ?, ?, ?, now(), ?, now(),"
                    + " ?, 0, ?)")) {
      ps.setObject(1, companyId);
      ps.setString(2, "Legacy Co");
      ps.setString(3, "IDR");
      ps.setString(4, "id");
      ps.setObject(5, companyId);
      ps.setString(6, "lgcy01");
      ps.setString(7, "migration-test");
      ps.setString(8, "migration-test");
      ps.setString(9, companyId.toString());
      ps.executeUpdate();
    }

    List<CompanyView> views =
        TenantContext.callAs(
            companyId.toString(), "migration-test", companyService::findCompaniesForCurrentTenant);

    assertThat(views).hasSize(1);
    // plan_tier is VARCHAR (unlike the CHAR(3)/CHAR(2) base_currency/country columns), so no
    // space-padding to strip — the raw projection value is compared directly.
    assertThat(views.getFirst().getPlanTier()).isEqualTo("FULL");
  }

  // ── (b) bad value -> 422-mapped domain exception ────────────────────────

  @Test
  void changePlanTierWithAnUnwhitelistedValueThrowsInvalidPlanTierException() throws Exception {
    UUID companyId = bootstrapCompany("Warung Budi", "owner-b");
    setRoles("owner");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyId.toString(), "owner-b", () -> companyService.changePlanTier("BOGUS")))
        .isInstanceOf(InvalidPlanTierException.class);
  }

  // ── (c) non-owner -> 403-mapped domain exception ────────────────────────

  @Test
  void changePlanTierAsAManagerIsForbidden() throws Exception {
    UUID companyId = bootstrapCompany("Warung Citra", "owner-c");
    setRoles("manager");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyId.toString(), "manager-c", () -> companyService.changePlanTier("FREE")))
        .isInstanceOf(PlanTierForbiddenException.class);
  }

  // ── (d) owner flips FREE <-> FULL and gets the updated body back ───────

  @Test
  void ownerFlipsPlanTierFreeToFullAndBackReturningTheUpdatedCompany() throws Exception {
    UUID companyId = bootstrapCompany("Warung Dewi", "owner-d");
    setRoles("owner");

    CompanyResponse afterFree =
        TenantContext.callAs(
            companyId.toString(), "owner-d", () -> companyService.changePlanTier("free"));
    assertThat(afterFree.planTier()).isEqualTo("FREE");
    assertThat(afterFree.id()).isEqualTo(companyId);

    CompanyResponse afterFull =
        TenantContext.callAs(
            companyId.toString(), "owner-d", () -> companyService.changePlanTier("FULL"));
    assertThat(afterFull.planTier()).isEqualTo("FULL");
  }

  // ── (e) BASIC is a whitelisted tier (ADR 0047 three-tier ladder) ────────

  @Test
  void ownerSetsTheBasicTier() throws Exception {
    UUID companyId = bootstrapCompany("Warung Eka", "owner-e");
    setRoles("owner");

    CompanyResponse afterBasic =
        TenantContext.callAs(
            companyId.toString(), "owner-e", () -> companyService.changePlanTier("basic"));

    assertThat(afterBasic.planTier()).isEqualTo("BASIC");
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
