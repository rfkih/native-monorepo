package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import java.time.Duration;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Security-critical edge proofs for the gateway: JWT validation (missing / invalid-signature /
 * expired / wrong-issuer / valid-but-tenant-less), downstream routing with trusted tenant-context
 * headers, and the client-spoof strip.
 *
 * <p>Real Keycloak + Redis + a recording MockWebServer downstream (see {@link
 * GatewayIntegrationTestBase}). A generous rate limit is configured so the limiter never interferes
 * with these assertions; the {@code 429} proofs live in their own classes (a tiny bucket, and a
 * fail-closed-with-Redis-down case). "Was/was-not forwarded" is asserted against the stub's
 * recorded request list, which is cleared per test — so the proofs are order-independent on the
 * shared stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwtRoutingTest extends GatewayIntegrationTestBase {

  @Test
  void aRequestWithNoBearerTokenIsRejectedWith401AndNeverReachesTheDownstream() {
    assertThatThrownBy(
            () -> gatewayClient().get().uri("/api/v1/sales/anything").retrieve().body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    // The stub must not have received anything — a tokenless request never crosses the edge.
    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void aValidTokenIsRoutedToTheDownstreamWithTenantContextHeadersDerivedFromTheClaims()
      throws Exception {
    String token = obtainAccessToken();

    RestClient client = gatewayClient();
    String response =
        client
            .get()
            .uri("/api/v1/sales/123")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            // The client TRIES to spoof its tenant/actor/roles — the gateway must overwrite these.
            .header(
                TenantContextHeaderFilter.COMPANY_HEADER, "99999999-9999-9999-9999-999999999999")
            .header(TenantContextHeaderFilter.ACTOR_HEADER, "attacker")
            .header(TenantContextHeaderFilter.ROLES_HEADER, "superadmin")
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");

    RecordedRequest forwarded = theForwardedRequest();
    // FIX 1: the FULL downstream path is asserted exactly — the gateway preserves /api/v1/sales/123
    // to restaurant-service, which now exposes that path. A route/path mismatch (the original
    // Critical) would land the stub on a different path and fail this assertion, not pass silently.
    assertThat(forwarded.getPath()).isEqualTo("/api/v1/sales/123");
    // X-Company-Id equals the token's company_id claim — NOT the spoofed client value.
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.COMPANY_HEADER))
        .isEqualTo(EXPECTED_COMPANY_ID);
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.COMPANY_HEADER))
        .isNotEqualTo("99999999-9999-9999-9999-999999999999");
    // X-Actor is the token subject, not the spoofed actor.
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ACTOR_HEADER))
        .isNotEqualTo("attacker")
        .isNotBlank();
    // X-Roles carries the curated business roles from the token (owner, manager), not the spoofed
    // value — and FIX 4: the Keycloak infrastructure noise (offline_access / uma_authorization /
    // default-roles-native) is filtered out, never leaked downstream.
    assertThat(forwarded.getHeader(TenantContextHeaderFilter.ROLES_HEADER))
        .contains("owner")
        .doesNotContain("superadmin")
        .doesNotContain("offline_access")
        .doesNotContain("uma_authorization")
        .doesNotContain("default-roles-native");
  }

  @Test
  void aValidButTenantlessTokenIsRejectedWith403AndNeverReachesTheDownstream() throws Exception {
    // A genuine, correctly-signed token for a `native`-realm user that has NO company_id attribute.
    String tenantless =
        obtainAccessToken(
            REALM, CLIENT_ID, CLIENT_SECRET, TENANTLESS_USERNAME, TENANTLESS_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/sales/123")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenantless))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                // Credentials are valid (not 401) but carry no tenant → 403 tenant/authZ denial.
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }

  @Test
  void anExpiredTokenIsRejectedWith401AndNeverReachesTheDownstream() throws Exception {
    // A token from the short-lived client (1s access-token lifespan, validated against the
    // gateway's
    // tight clock skew). Awaitility polls until the gateway sees it as expired and returns 401 —
    // deterministic, no Thread.sleep. Pre-expiry the token is still valid and legitimately routes,
    // so we only assert "never forwarded" AFTER expiry: clear the recorded list once 401 is seen,
    // then make one more expired call and assert nothing crossed the edge.
    String shortLived =
        obtainAccessToken(
            REALM, SHORTLIVED_CLIENT_ID, SHORTLIVED_CLIENT_SECRET, USERNAME, PASSWORD);

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(statusVia(shortLived)).isEqualTo(HttpStatus.UNAUTHORIZED));

    // Token is now firmly expired: a fresh call is 401 and never reaches the downstream stub.
    receivedRequests.clear();
    assertThat(statusVia(shortLived)).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(receivedRequests).isEmpty();
  }

  /** Sends one GET with the given bearer token and returns the gateway's status (no body throw). */
  private HttpStatus statusVia(String token) {
    return gatewayClient()
        .get()
        .uri("/api/v1/sales/123")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))
        .exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
  }

  @Test
  void aTokenFromADifferentIssuerIsRejectedWith401AndNeverReachesTheDownstream() throws Exception {
    // A correctly-signed token from the SECOND realm — its iss is `other`, not the trusted `native`
    // issuer, so the resource-server issuer check rejects it (proves issuer validation is real).
    String foreign =
        obtainAccessToken(
            OTHER_REALM, OTHER_CLIENT_ID, OTHER_CLIENT_SECRET, OTHER_USERNAME, OTHER_PASSWORD);

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/sales/123")
                    .header(HttpHeaders.AUTHORIZATION, bearer(foreign))
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
  void aTokenWithAnInvalidSignatureIsRejectedWith401AndNeverReachesTheDownstream()
      throws Exception {
    // A structurally-valid JWT signed by a different (unknown) key — fails JWKS signature check.
    String forged =
        obtainAccessToken().substring(0, obtainAccessToken().lastIndexOf('.') + 1) + "tampered";

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/sales/x")
                    .header(HttpHeaders.AUTHORIZATION, bearer(forged))
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
  void healthzIsExemptFromAuthenticationAndAnswersWithoutAToken() {
    String body = gatewayClient().get().uri("/healthz").retrieve().body(String.class);
    assertThat(body).contains("UP").contains("gateway");
  }
}
