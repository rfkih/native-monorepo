package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The preset role-based access model, Phase 1 (4 new roles: {@code hr}, {@code accountant}, {@code
 * chef}, {@code waitress}) — the capability matrix the gateway now enforces on top of the original
 * {@code owner}/{@code manager}/{@code cashier}/{@code employee}.
 *
 * <ul>
 *   <li>{@code hr} → {@code HR_ROLES}/{@code PAYROLL_ROLES} surfaces only.
 *   <li>{@code accountant} → {@code REPORTS_ROLES}/{@code FINANCE_ROLES} surfaces only.
 *   <li>{@code chef}/{@code waitress} → the {@code POS_ROLES} till surface only, exactly like a
 *       cashier — no back-office reach whatsoever.
 *   <li>{@code manager} → {@code OPS_ROLES}/{@code REPORTS_ROLES}/{@code HR_ROLES} (operations +
 *       reports + HR), but NOT {@code FINANCE_ROLES} (the detailed books) nor {@code PAYROLL_ROLES}
 *       — a manager runs the business, not the books or payroll.
 *   <li>A login holding several roles (e.g. {@code hr}+{@code accountant}) gets the UNION of every
 *       role's surfaces.
 * </ul>
 *
 * <p>Mirrors {@link GatewayRoleRoutingTest}'s style/fixtures (same real-Keycloak base, same
 * downstream-stub forwarding assertions). See {@code RoutingConfig}'s capability-array javadoc for
 * the full route → role-array table this test proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoleExpansionTest extends GatewayIntegrationTestBase {

  private static final String HR_USERNAME = "hr-acme";
  private static final String HR_PASSWORD = "hr-password";

  private static final String ACCOUNTANT_USERNAME = "accountant-acme";
  private static final String ACCOUNTANT_PASSWORD = "accountant-password";

  private static final String CHEF_USERNAME = "chef-acme";
  private static final String CHEF_PASSWORD = "chef-password";

  private static final String WAITRESS_USERNAME = "waitress-acme";
  private static final String WAITRESS_PASSWORD = "waitress-password";

  // A MANAGER-ONLY user (realmRoles: ["manager"] only, no "owner") — the same fixture
  // GatewayRoleRoutingTest uses for the owner/manager boundary; owner-acme carries BOTH roles and
  // so
  // can never prove a manager-only token is denied a manager-excluded route.
  private static final String MANAGER_USERNAME = "manager-acme";
  private static final String MANAGER_PASSWORD = "manager-password";

  // A multi-role login: hr AND accountant on the SAME token — proves access is the union.
  private static final String HR_ACCOUNTANT_USERNAME = "hr-accountant-acme";
  private static final String HR_ACCOUNTANT_PASSWORD = "hr-accountant-password";

  // ---------------------------------------------------------------------------
  // hr: payroll + HR yes, finance/reports no
  // ---------------------------------------------------------------------------

  @Test
  void anHrRoleCanReachThePayrollRunsRoute() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

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
  void anHrRoleCanReachTheEmployeesRoute() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/employees")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/employees");
  }

  @Test
  void anHrRoleIsDeniedTheApBillsRouteWith403() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/ap/bills")
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
  void anHrRoleIsDeniedTheStatementsIncomeRouteWith403() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

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

  // ---------------------------------------------------------------------------
  // accountant: finance + reports yes, payroll/HR no
  // ---------------------------------------------------------------------------

  @Test
  void anAccountantRoleCanReachTheApBillsRoute() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, ACCOUNTANT_USERNAME, ACCOUNTANT_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/ap/bills")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/ap/bills");
  }

  @Test
  void anAccountantRoleCanReachTheStatementsIncomeRoute() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, ACCOUNTANT_USERNAME, ACCOUNTANT_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/income")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/statements/income");
  }

  @Test
  void anAccountantRoleIsDeniedThePayrollRunsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, ACCOUNTANT_USERNAME, ACCOUNTANT_PASSWORD);

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
  void anAccountantRoleIsDeniedTheEmployeesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, ACCOUNTANT_USERNAME, ACCOUNTANT_PASSWORD);

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

  // ---------------------------------------------------------------------------
  // chef / waitress: ring the till like a cashier, no back-office reach
  // ---------------------------------------------------------------------------

  @Test
  void aChefCanReachTheOrdersRoute() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CHEF_USERNAME, CHEF_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/orders/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/orders/x");
  }

  @Test
  void aChefIsDeniedTheEmployeesRouteWith403() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CHEF_USERNAME, CHEF_PASSWORD);

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
  void aChefIsDeniedTheApBillsRouteWith403() throws Exception {
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CHEF_USERNAME, CHEF_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/ap/bills")
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
  void aWaitressCanReachTheOrdersRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, WAITRESS_USERNAME, WAITRESS_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/orders/x")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/orders/x");
  }

  @Test
  void aWaitressIsDeniedTheEmployeesRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, WAITRESS_USERNAME, WAITRESS_PASSWORD);

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
  void aWaitressIsDeniedTheApBillsRouteWith403() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, WAITRESS_USERNAME, WAITRESS_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/ap/bills")
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
  // manager: operations + reports + HR yes, detailed finance + payroll no
  // ---------------------------------------------------------------------------

  @Test
  void aManagerCanReachTheOpsOrgUnitsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

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
  void aManagerCanReachTheReportsStatementsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/income")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/statements/income");
  }

  @Test
  void aManagerCanReachTheHrEmployeesRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/employees")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/employees");
  }

  @Test
  void aManagerCanReachTheHrLeaveRequestsRoute() throws Exception {
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/leave-requests")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/leave-requests");
  }

  @Test
  void aManagerIsDeniedTheApBillsRouteWith403() throws Exception {
    // Detailed finance (the books) is owner/accountant only — a manager runs operations, not the
    // ledger.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/ap/bills")
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
  void aManagerIsDeniedThePayrollRunsRouteWith403() throws Exception {
    // Payroll is owner/hr only — a manager does not run payroll.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MANAGER_USERNAME, MANAGER_PASSWORD);

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

  // ---------------------------------------------------------------------------
  // org-units: every OFFICE role may READ the tree (unit names for HR/reports),
  // but only owner/manager may WRITE (create/rename/move/deactivate a unit).
  // ---------------------------------------------------------------------------

  @Test
  void anHrRoleCanReadTheOrgUnitsTree() throws Exception {
    // The HR/People area needs unit names to scope employees + payroll — a READ, not management.
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

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
  void anAccountantRoleCanReadTheOrgUnitsTree() throws Exception {
    // Per-unit reports (P&L) name their units — accountant reads the tree too.
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, ACCOUNTANT_USERNAME, ACCOUNTANT_PASSWORD);

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
  void anHrRoleIsDeniedWritingOrgUnitsWith403() throws Exception {
    // Reading the tree is widened to office roles; MANAGING it (POST/PUT/DELETE) stays
    // owner/manager.
    // A write verb does not match the GET read route and falls through to the OPS-only route → 403.
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, HR_USERNAME, HR_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .post()
                    .uri("/api/v1/org-units")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .body("{}")
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
  void aChefIsDeniedReadingTheOrgUnitsTreeWith403() throws Exception {
    // Floor roles are NOT office roles — they use the narrower GET /api/v1/outlets picker, never
    // the
    // org-unit tree.
    String token = obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CHEF_USERNAME, CHEF_PASSWORD);

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

  // ---------------------------------------------------------------------------
  // Multi-role login (hr + accountant on the SAME token): access is the union
  // ---------------------------------------------------------------------------

  @Test
  void aMultiRoleHrAccountantLoginCanReachThePayrollRunsRoute() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, HR_ACCOUNTANT_USERNAME, HR_ACCOUNTANT_PASSWORD);

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
  void aMultiRoleHrAccountantLoginCanReachTheApBillsRoute() throws Exception {
    String token =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, HR_ACCOUNTANT_USERNAME, HR_ACCOUNTANT_PASSWORD);

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/ap/bills")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/ap/bills");
  }
}
