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
