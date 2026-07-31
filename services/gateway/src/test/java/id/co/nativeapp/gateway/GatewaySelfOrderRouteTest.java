package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The gateway's one anonymous BUSINESS route: {@code /api/v1/self-order/**} (Phase 6 self-order QR,
 * ADR 0029), proxied to restaurant-service with no bearer token required.
 *
 * <p>Proves the properties that make an anonymous route safe: it is reachable with no {@code
 * Authorization} header (no {@code 401}); a client cannot spoof a trusted tenant/actor/role header
 * onto the downstream request — {@code AnonymousTenantHeaderStripFilter} strips it, it is not
 * merely absent; and the diner-issued {@code X-Self-Order-Token} — the ONLY identity
 * restaurant-service trusts on this route — rides through untouched. A final pair of regression
 * tests proves the authenticated routes (bearer requirement + trusted tenant-header injection) are
 * unaffected by wiring this one anonymous exception in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySelfOrderRouteTest extends GatewayIntegrationTestBase {

  private static final String CASHIER_USERNAME = "cashier-acme";
  private static final String CASHIER_PASSWORD = "cashier-password";

  @Test
  void anAnonymousRequestReachesTheRouteWithNoBearerTokenAndNo401() {
    String response =
        gatewayClient().get().uri("/api/v1/self-order/menu").retrieve().body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getPath()).isEqualTo("/api/v1/self-order/menu");
  }

  @Test
  void spoofedTenantContextHeadersAreStrippedBeforeProxying() {
    gatewayClient()
        .get()
        .uri("/api/v1/self-order/menu")
        // The caller TRIES to inject a trusted tenant/actor/role — there is no JWT behind these
        // values, so they must never reach the downstream.
        .header(TenantContextHeaderFilter.COMPANY_HEADER, EXPECTED_COMPANY_ID)
        .header(TenantContextHeaderFilter.ROLES_HEADER, "owner")
        .header(TenantContextHeaderFilter.ACTOR_HEADER, "attacker")
        .retrieve()
        .body(String.class);

    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.COMPANY_HEADER)).isNull();
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ROLES_HEADER)).isNull();
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ACTOR_HEADER)).isNull();
  }

  @Test
  void theSelfOrderTokenHeaderPassesThroughUntouched() {
    gatewayClient()
        .get()
        .uri("/api/v1/self-order/menu")
        .header("X-Self-Order-Token", "qr-session-token-abc123")
        .retrieve()
        .body(String.class);

    assertThat(theForwardedRequest().getHeader("X-Self-Order-Token"))
        .isEqualTo("qr-session-token-abc123");
  }

  // ---------------------------------------------------------------------------
  // Regression: wiring in the one anonymous exception must not weaken any other route.
  // ---------------------------------------------------------------------------

  @Test
  void anAuthenticatedRouteStillRejectsAMissingBearerTokenWith401() {
    assertThatThrownBy(
            () -> gatewayClient().get().uri("/api/v1/sales/anything").retrieve().body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anAuthenticatedRouteStillGetsItsTrustedTenantHeadersFromTheJwt() throws Exception {
    String token = obtainAccessToken();
    RestClient client = gatewayClient();

    String response =
        client
            .get()
            .uri("/api/v1/sales/123")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.COMPANY_HEADER))
        .isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ACTOR_HEADER)).isNotBlank();
  }

  // ---------------------------------------------------------------------------
  // /api/v1/self-order-access/** — the QR ADMINISTRATION surface (F-1 fix): an ordinary
  // AUTHENTICATED dashboard route (owner/manager), NOT part of the anonymous diner surface above.
  // ---------------------------------------------------------------------------

  @Test
  void theSelfOrderAccessRouteRejectsAMissingBearerTokenWith401() {
    // The core of the F-1 fix: /api/v1/self-order-access/** is a SIBLING path segment to
    // /api/v1/self-order/** (Spring's ** pattern does not match across the extra "-access"
    // characters), so it is NOT covered by the anonymous selfOrderRoute above. An unauthenticated
    // request must be rejected 401, exactly like any other dashboard route — proving the mint/
    // rotate admin endpoints are no longer reachable without a bearer token.
    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/self-order-access/some-outlet")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void aCashierIsDeniedTheSelfOrderAccessRouteWith403() throws Exception {
    // DASHBOARD_ROLES only — the cashier POS role has no business minting/rotating a table's QR
    // tokens.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, CASHIER_USERNAME, CASHIER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/self-order-access/some-outlet")
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
  void anOwnerCanReachTheSelfOrderAccessRouteWithInjectedTrustedTenantHeaders() throws Exception {
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/self-order-access/some-outlet")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/self-order-access/some-outlet");
    // Unlike the anonymous self-order route, this dashboard route DOES carry the gateway's own
    // trusted tenant/actor/role headers (TenantContextHeaderFilter, not the strip-only filter).
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.COMPANY_HEADER))
        .isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ACTOR_HEADER)).isNotBlank();
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ROLES_HEADER)).isNotBlank();
  }
}
