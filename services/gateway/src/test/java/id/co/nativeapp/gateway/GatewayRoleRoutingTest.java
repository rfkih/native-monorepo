package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Role-based surface separation at the edge (the API half of "the POS and the dashboard are not the
 * same interface"). A {@code cashier} token may reach the POS routes but is denied the
 * owner-dashboard routes with {@code 403}, while an {@code owner} token reaches both. The denial
 * happens at the gateway — the request never crosses to a downstream service.
 *
 * <p>Real Keycloak (the {@code cashier-acme} user has the {@code cashier} role + a {@code
 * company_id}, so the tenant filter passes and it is the ROLE gate being exercised, not a missing
 * tenant).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoleRoutingTest extends GatewayIntegrationTestBase {

  private static final String CASHIER_USERNAME = "cashier-acme";
  private static final String CASHIER_PASSWORD = "cashier-password";

  @Test
  void aCashierCanReachThePosMenuRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/menu/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/menu/x");
  }

  @Test
  void aCashierCanReachThePosPaymentsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payments/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/payments/x");
  }

  @Test
  void aCashierCanReachThePosTablesRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/tables/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/tables/x");
  }

  @Test
  void aCashierIsDeniedTheDashboardPnlRouteWith403AndItNeverReachesTheDownstream()
      throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/pnl/2026-06")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void aCashierIsDeniedTheDashboardRevenueRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/revenue/2026-06")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheDashboardPnlRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/pnl/2026-06")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/pnl/2026-06");
  }

  @Test
  void aCashierIsDeniedTheDashboardStatementsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/statements/income")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheDashboardStatementsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/balance-sheet")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/statements/balance-sheet");
  }

  @Test
  void aCashierIsDeniedTheDashboardTaxRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/tax/vat/returns")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheDashboardTaxRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/tax/vat/returns")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/tax/vat/returns");
  }

  @Test
  void aCashierIsDeniedTheDashboardBudgetsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/budgets")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheDashboardBudgetsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/budgets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/budgets");
  }

  // --- New dashboard routes: org-units, consolidation-groups, groups, closes ---

  @Test
  void aCashierIsDeniedTheOrgUnitsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/org-units")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheOrgUnitsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/org-units")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/org-units");
  }

  @Test
  void aCashierIsDeniedTheConsolidationGroupsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/consolidation-groups")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheConsolidationGroupsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/consolidation-groups")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/consolidation-groups");
  }

  @Test
  void aCashierIsDeniedTheGroupsConsolidationRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/groups/some-group/consolidation")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheGroupsConsolidationRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/groups/some-group/consolidation")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/groups/some-group/consolidation");
  }

  @Test
  void aCashierIsDeniedTheClosesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/closes")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheClosesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/closes")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/closes");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/outlets — POS outlet picker (cashier-allowed; distinct from /org-units)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheOutletsRoute() throws Exception {
    // Cashiers are the primary POS users and need the outlet picker to open a sale.
    // Regression guard: the outletsRoute bean must be POS_ROLES-gated (not DASHBOARD_ROLES),
    // fixing the review-critical where cashiers were denied their own outlet list.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/outlets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/outlets");
  }

  @Test
  void anOwnerCanAlsoReachTheOutletsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/outlets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/outlets");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/companies/current — the caller's OWN company (POS needs it → cashier-allowed)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheCurrentCompanyRoute() throws Exception {
    // The cashier POS loads GET /companies/current for the company's firstBusinessId +
    // name/currency.
    // It is a tenant-scoped read of the caller's own company, so a cashier MUST be allowed —
    // without
    // this the POS shows "No company selected" (regression guard for the currentCompanyRoute bean).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/companies/current")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/companies/current");
  }

  @Test
  void anOwnerCanReachTheCurrentCompanyRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/companies/current")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/companies/current");
  }

  @Test
  void aCashierIsDeniedOtherCompaniesRoutesWith403() throws Exception {
    // Only the EXACT /companies/current path is cashier-allowed; every other /companies/** path
    // (company creation / management) stays owner/manager-only — proves the route ordering is
    // specific, not a blanket broadening of the companies surface.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/companies/" + EXPECTED_COMPANY_ID)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // /api/v1/users — team management (owner/manager dashboard route)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheUsersRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/users")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheUsersRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/users");
  }

  @Test
  void theUsersRouteInjectsTenantHeadersFromTheJwt() throws Exception {
    String token = obtainAccessToken();

    gatewayClient()
        .get()
        .uri("/api/v1/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))
        .retrieve()
        .body(String.class);

    assertThat(receivedRequests).hasSize(1);
    okhttp3.mockwebserver.RecordedRequest forwarded = theForwardedRequest();
    // The gateway must inject the JWT-derived tenant headers.
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
    assertThat(forwarded.getHeader("X-Roles")).isNotBlank();
  }

  // ---------------------------------------------------------------------------
  // /api/v1/users/me/outlets — caller's own outlet assignments (cashier-allowed)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheMeOutletsRoute() throws Exception {
    // Cashiers are the primary POS users; they must be able to read their own outlet assignments
    // for the POS picker intersection. The /me/outlets route is POS_ROLES-gated and must be ordered
    // BEFORE the general /users/** route at the gateway so the cashier is not 403'd by the
    // dashboard-gated users route.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/users/me/outlets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/users/me/outlets");
  }

  @Test
  void anOwnerCanAlsoReachTheMeOutletsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/users/me/outlets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/users/me/outlets");
  }

  @Test
  void aCashierIsStillDeniedTheGeneralUsersRouteWith403() throws Exception {
    // Regression guard: /me/outlets being POS-allowed must NOT broaden the general /users/ surface.
    // A cashier must still be denied the general team-management list (/users, not /users/me).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/users")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void aCashierIsStillDeniedUsersByIdOutletsRouteWith403() throws Exception {
    // A cashier must NOT be able to read another user's outlet assignments via /{userId}/outlets
    // (only their own via /me/outlets). The /{userId}/outlets path falls through to the
    // DASHBOARD_ROLES-gated /users/** route.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/users/some-user-id/outlets")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // employee-service HR/payroll routes — owner/manager dashboard surface only
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheEmployeesRouteWith403() throws Exception {
    // HR records carry PII and salary state — strictly a dashboard surface, never POS.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/employees")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachTheEmployeesRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/employees/some-id/assignments")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/employees/some-id/assignments");
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
  }

  @Test
  void aCashierIsDeniedThePayrollRunsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-runs?period=2026-07")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachThePayrollRunsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-runs?period=2026-07")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/payroll-runs?period=2026-07");
  }

  @Test
  void aCashierIsDeniedThePayrollSetupRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-setup")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anOwnerCanReachThePayrollSetupRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-setup")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/payroll-setup");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/me/** — employee self-service (every business role)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheMeSurface() throws Exception {
    // ME_ROLES includes every business role — the downstream resolves the caller from the
    // injected X-Actor (the sub), so there is nothing to widen.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/me/profile")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/me/profile");
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
  }

  @Test
  void anOwnerCanAlsoReachTheMeSurface() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/me/payslips")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/me/payslips");
  }

  // ---------------------------------------------------------------------------
  // Public sign-up route — no Authorization header required
  // ---------------------------------------------------------------------------

  @Test
  void signupIsReachableWithNoAuthorizationHeader() throws Exception {
    // The signup route must be reachable with NO Authorization header — the gateway's
    // SecurityConfig
    // permitAlls /api/v1/signup and the signupRoute bean applies no RoleAuthorizationFilter.
    String response =
        gatewayClient()
            .post()
            .uri("/api/v1/signup")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(
                """
                {"companyName":"Test","baseCurrency":"IDR","defaultLanguage":"id",
                 "firstBusinessName":"Outlet","firstBusinessType":"outlet",
                 "ownerEmail":"test@example.co.id","ownerPassword":"secret"}
                """)
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    // The request was forwarded to org-service (the stub).
    assertThat(receivedRequests).hasSize(1);
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/signup");
  }

  @Test
  void signupForwardedRequestDoesNotCarryTenantOrActorOrRolesHeaders() throws Exception {
    // The TenantContextHeaderFilter is NOT applied on the signup route. The downstream must NOT
    // receive X-Company-Id / X-Actor / X-Roles headers (they come only from a validated JWT, and
    // the signup request has no JWT).
    gatewayClient()
        .post()
        .uri("/api/v1/signup")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            """
            {"companyName":"Test","baseCurrency":"IDR","defaultLanguage":"id",
             "firstBusinessName":"Outlet","firstBusinessType":"outlet",
             "ownerEmail":"test2@example.co.id","ownerPassword":"secret"}
            """)
        .retrieve()
        .body(String.class);

    assertThat(receivedRequests).hasSize(1);
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getHeader("X-Company-Id")).isNull();
    assertThat(forwarded.getHeader("X-Actor")).isNull();
    assertThat(forwarded.getHeader("X-Roles")).isNull();
  }
}
