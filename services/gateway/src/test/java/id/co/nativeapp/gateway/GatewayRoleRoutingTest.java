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

  // A MANAGER-ONLY user (realmRoles: ["manager"] only, no "owner") — W2 review fix. owner-acme
  // itself carries BOTH "owner" AND "manager" realm roles, so it can never prove a manager-only
  // token is denied an owner-only route (it would pass on the "owner" role alone); a cashier is
  // denied everywhere anyway and proves nothing about the owner/manager boundary specifically.
  // This is the ONLY persona the owner-only gate (bank-file, payslip /authorized, 1721a1,
  // bpjs-summary) actually exists to exclude.
  private static final String MANAGER_USERNAME = "manager-acme";
  private static final String MANAGER_PASSWORD = "manager-password";

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
  void aCashierCanReachTheRegisterSessionsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/register-sessions/current?businessId=x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/register-sessions/current?businessId=x");
  }

  @Test
  void aCashierCanReachTheStocktakesRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/stocktakes?businessId=x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/stocktakes?businessId=x");
  }

  @Test
  void aCashierCanReachTheSalesChannelsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/sales-channels")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/sales-channels");
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
  void aCashierIsDeniedThePlatformSettlementsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/platform-settlements/outstanding")
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
  void anOwnerCanReachThePlatformSettlementsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/platform-settlements/outstanding")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/platform-settlements/outstanding");
  }

  @Test
  void aCashierIsDeniedTheOpeningBalancesRouteWith403() throws Exception {
    // Opening balances (ADR 0037) post money (Dr assets / Cr liabilities + equity) — a back-office
    // finance action with no cashier surface.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/opening-balances")
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
  void anOwnerCanReachTheOpeningBalancesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/opening-balances")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/opening-balances");
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

  @Test
  void aCashierIsDeniedTheDashboardAssetsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/assets")
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
  void anOwnerCanReachTheDashboardAssetsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/assets")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/assets");
  }

  @Test
  void anOwnerCanReachTheDashboardDeferralsRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/deferrals")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/deferrals");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/payroll-liabilities/** — liability read + settlement (finance-service, ADR 0032 P5)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedThePayrollLiabilitiesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-liabilities?period=2026-07")
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
  void anOwnerCanReachThePayrollLiabilitiesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-liabilities?period=2026-07")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-liabilities?period=2026-07");
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
  // /api/v1/payroll-runs/{runId}/bank-file — the net-pay bank file, OWNER-ONLY (ADR 0032, P5)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheBankFileRouteWith403() throws Exception {
    // Bank-account PII — narrower than the DASHBOARD_ROLES payroll-runs surface (owner-only, not
    // owner/manager).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-runs/some-run-id/bank-file")
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
  void anOwnerCanReachTheBankFileRoute() throws Exception {
    // The HIGHEST_PRECEDENCE-ordered specific route must be checked BEFORE the general
    // /api/v1/payroll-runs/** (DASHBOARD_ROLES) route, so an owner token reaches it.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-runs/some-run-id/bank-file")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-runs/some-run-id/bank-file");
  }

  @Test
  void aManagerIsDeniedTheBankFileRouteWith403() throws Exception {
    // W2 review fix: the persona the owner-only gate EXISTS to exclude is the MANAGER (a cashier
    // is denied everywhere anyway, proving nothing about the owner/manager boundary specifically).
    // manager-acme carries ONLY the "manager" realm role — unlike owner-acme, which also carries
    // "manager" and so could never prove this on its own.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-runs/some-run-id/bank-file")
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
  void theBankFileRouteDoesNotShadowOtherPayrollRunsSubPaths() throws Exception {
    // Regression guard (route-precedence proof, the other direction): the narrow
    // /payroll-runs/*/bank-file wildcard must NOT swallow an unrelated /payroll-runs/** sub-path
    // (e.g. /payslips) — those still fall through to the DASHBOARD_ROLES payrollRunsRoute, so a
    // manager-eligible caller reaches it exactly as before this phase.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-runs/some-run-id/payslips")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-runs/some-run-id/payslips");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/payroll-runs/{runId}/payslips/{employeeId}/authorized — REAL payslip amounts for
  // printing, OWNER-ONLY (Track P phase P9)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedThePayslipAuthorizedRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id/authorized")
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
  void anOwnerCanReachThePayslipAuthorizedRoute() throws Exception {
    // The HIGHEST_PRECEDENCE-ordered specific route must be checked BEFORE the general
    // /api/v1/payroll-runs/** (DASHBOARD_ROLES) route, so an owner token reaches it.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id/authorized")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id/authorized");
  }

  @Test
  void aManagerIsDeniedThePayslipAuthorizedRouteWith403() throws Exception {
    // W2 review fix — see aManagerIsDeniedTheBankFileRouteWith403's javadoc for why a manager
    // fixture (not just cashier) is required to prove this owner-only gate.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id/authorized")
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
  void thePayslipAuthorizedRouteDoesNotShadowTheMaskedPayslipSubPath() throws Exception {
    // Regression guard (the other direction): the narrow .../authorized wildcard must not swallow
    // the masked .../payslips/{employeeId} path — that still falls through to payrollRunsRoute.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-runs/some-run-id/payslips/some-employee-id");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/payroll-reports/** — statutory CSV exports (Track P phase P9): 1721a1 + bpjs-summary
  // are OWNER-ONLY, pph21-monthly is the ordinary owner/manager DASHBOARD_ROLES aggregate
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedThe1721A1ReportRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-reports/1721a1?year=2026")
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
  void anOwnerCanReachThe1721A1ReportRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-reports/1721a1?year=2026")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-reports/1721a1?year=2026");
  }

  @Test
  void aManagerIsDeniedThe1721A1ReportRouteWith403() throws Exception {
    // W2 review fix — see aManagerIsDeniedTheBankFileRouteWith403's javadoc.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-reports/1721a1?year=2026")
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
  void aCashierIsDeniedTheBpjsSummaryReportRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-reports/bpjs-summary?period=2026-07")
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
  void anOwnerCanReachTheBpjsSummaryReportRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-reports/bpjs-summary?period=2026-07")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-reports/bpjs-summary?period=2026-07");
  }

  @Test
  void aManagerIsDeniedTheBpjsSummaryReportRouteWith403() throws Exception {
    // W2 review fix — see aManagerIsDeniedTheBankFileRouteWith403's javadoc.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-reports/bpjs-summary?period=2026-07")
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
  void aCashierIsDeniedThePph21MonthlyReportRouteWith403() throws Exception {
    // pph21-monthly is a DASHBOARD_ROLES route (owner/manager) — still not the cashier POS surface.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/payroll-reports/pph21-monthly?period=2026-07")
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
  void anOwnerCanReachThePph21MonthlyReportRoute() throws Exception {
    // The general DASHBOARD_ROLES /api/v1/payroll-reports/** route serves this — not one of the
    // two HIGHEST_PRECEDENCE owner-only carve-outs above.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-reports/pph21-monthly?period=2026-07")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-reports/pph21-monthly?period=2026-07");
  }

  @Test
  void aManagerCanReachThePph21MonthlyReportRoute() throws Exception {
    // W2 review fix: pph21-monthly is the ordinary DASHBOARD_ROLES aggregate (owner OR manager) —
    // the positive counterpart to the three owner-only manager-denied tests above, proving the
    // manager fixture genuinely reaches a DASHBOARD_ROLES route rather than being denied globally.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/payroll-reports/pph21-monthly?period=2026-07")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/payroll-reports/pph21-monthly?period=2026-07");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/expense-claims/** + /api/v1/expense-categories/** — expense claims (ADR 0030, E1)
  // owner/manager dashboard surface only; the employee self-service half rides /api/v1/me/**
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheExpenseClaimsRouteWith403() throws Exception {
    // The manager decision surface (approve/refuse/list) — never POS.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/expense-claims")
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
  void anOwnerCanReachTheExpenseClaimsRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/expense-claims")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/expense-claims");
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
  }

  @Test
  void aCashierIsDeniedTheExpenseCategoriesRouteWith403() throws Exception {
    // The category admin catalog — config an employee never touches directly.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/expense-categories")
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
  void anOwnerCanReachTheExpenseCategoriesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/expense-categories")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/expense-categories");
  }

  @Test
  void aCashierCanStillReachTheirOwnExpenseClaimsUnderMe() throws Exception {
    // Regression guard: the new DASHBOARD_ROLES-gated /expense-claims/** route must NOT narrow
    // the pre-existing /api/v1/me/** surface, which already carries /api/v1/me/expense-claims/**
    // for every business role (ME_ROLES).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/me/expense-claims")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/me/expense-claims");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/leave-requests/**, /api/v1/overtime-entries/**, /api/v1/work-calendar/**,
  // /api/v1/leave-balances/** — attendance & time-off (ADR 0033, Track P Phase P6);
  // owner/manager dashboard surface only; the employee self-service half rides /api/v1/me/**
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheLeaveRequestsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/leave-requests")
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
  void anOwnerCanReachTheLeaveRequestsRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/leave-requests")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/leave-requests");
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
  }

  @Test
  void aCashierIsDeniedTheOvertimeEntriesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/overtime-entries")
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
  void anOwnerCanReachTheOvertimeEntriesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/overtime-entries")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/overtime-entries");
  }

  @Test
  void aCashierIsDeniedTheWorkCalendarRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/work-calendar")
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
  void anOwnerCanReachTheWorkCalendarRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/work-calendar")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/work-calendar");
  }

  @Test
  void aCashierIsDeniedTheLeaveBalancesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/leave-balances")
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
  void anOwnerCanReachTheLeaveBalancesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/leave-balances")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/leave-balances");
  }

  @Test
  void aCashierCanStillReachTheirOwnLeaveRequestsUnderMe() throws Exception {
    // Regression guard: the new DASHBOARD_ROLES-gated /leave-requests/** route must NOT narrow
    // the pre-existing /api/v1/me/** surface, which already carries /api/v1/me/leave-requests/**
    // for every business role (ME_ROLES).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/me/leave-requests")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/me/leave-requests");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/pricing/** — restaurant pricing-rule preview (POS_ROLES, offline mode, ADR 0028)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachThePosPricingRoute() throws Exception {
    // A POS client (cashier) reads the effective tax/service-charge rules to cache for offline
    // pricing (Phase 5, ADR 0028) — the same POS_ROLES gate as the other restaurant POS routes.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/pricing/effective-rules?businessId=" + EXPECTED_COMPANY_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath())
        .isEqualTo("/api/v1/pricing/effective-rules?businessId=" + EXPECTED_COMPANY_ID);
  }

  @Test
  void anOwnerCanReachThePosPricingRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/pricing/effective-rules?businessId=" + EXPECTED_COMPANY_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath())
        .isEqualTo("/api/v1/pricing/effective-rules?businessId=" + EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
    assertThat(forwarded.getHeader("X-Roles")).isNotBlank();
  }

  // ---------------------------------------------------------------------------
  // /api/v1/carwash/** — the carwash vertical POS surface (POS_ROLES, vertical-prefixed)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheCarwashPackagesRoute() throws Exception {
    // Carwash is the second vertical POS; its whole surface is vertical-prefixed
    // (/api/v1/carwash/**) and POS_ROLES-gated like the restaurant POS routes.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/carwash/packages")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/carwash/packages");
  }

  @Test
  void anOwnerCanReachTheCarwashRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/carwash/tickets/some-id")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/carwash/tickets/some-id");
    // The new route bean must apply the tenant filter like every other authenticated route.
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader("X-Actor")).isNotBlank();
    assertThat(forwarded.getHeader("X-Roles")).isNotBlank();
  }

  // ---------------------------------------------------------------------------
  // /api/v1/barbershop/** — the barbershop vertical POS surface (POS_ROLES, vertical-prefixed)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheBarbershopServicesRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/barbershop/services")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/barbershop/services");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/loyalty/** — member/gift-card POS surface (POS_ROLES) with earn-rules
  // carved out to the dashboard (ADR 0027)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierCanReachTheLoyaltyMembersRoute() throws Exception {
    // The cashier attaches members and gift cards at the till — a POS surface. PII stays inside
    // loyalty-service; the route only fronts masked lookups.
    //
    // POST, not GET (security review W-2): the phone lookup moved from a query parameter to a
    // request body so the phone never lands in a proxy access log / trace span. The route itself
    // is unchanged (still /api/v1/loyalty/**), only the sub-path + verb changed.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .post()
            .uri("/api/v1/loyalty/members/lookup")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .body("{\"phone\":\"0812\"}")
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/loyalty/members/lookup");
  }

  @Test
  void aCashierCanReachTheGiftCardSalesRoute() throws Exception {
    // Selling a gift card is a till operation (ADR 0027) — POS surface, restaurant-unprefixed.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/gift-card-sales/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/gift-card-sales/x");
  }

  @Test
  void aCashierIsDeniedTheLoyaltyEarnRulesRouteWith403() throws Exception {
    // Earn rules decide how much every sale is worth in points — owner/manager config, ordered
    // BEFORE the general loyalty route (first match wins).
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/loyalty/earn-rules")
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
  void anOwnerCanReachTheLoyaltyEarnRulesRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/loyalty/earn-rules")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/loyalty/earn-rules");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/promotions/** — promo rules + coupons admin (owner/manager only, ADR 0026)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedThePromotionsAdminRouteWith403() throws Exception {
    // Rules and coupon codes change what customers pay — a back-office surface, never POS. The
    // cashier-facing coupon APPLY rides the POS order routes, not this one.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/promotions")
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
  void anOwnerCanReachThePromotionsAdminRoute() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/promotions/coupons")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/promotions/coupons");
  }

  // ---------------------------------------------------------------------------
  // /api/v1/entitlements/** — module self-serve (owner/manager dashboard surface only)
  // ---------------------------------------------------------------------------

  @Test
  void aCashierIsDeniedTheEntitlementsRouteWith403() throws Exception {
    // Module grants change what a company pays for — strictly a dashboard surface, never POS.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/entitlements")
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
  void anOwnerCanReachTheEntitlementsRouteWithInjectedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/entitlements")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/entitlements");
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
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
