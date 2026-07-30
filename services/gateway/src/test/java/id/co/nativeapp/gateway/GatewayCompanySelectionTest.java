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
 * Active-company selection at the edge (multi-company ownership, ADR 0021). The {@code owner-multi}
 * seed user's token carries TWO companies in the {@code company_id} claim (a JSON array — the
 * multivalued mapper). The gateway must:
 *
 * <ul>
 *   <li>default to the FIRST allowed company when the client sends no selection;
 *   <li>honour an {@code X-Company-Id} selection that IS in the token's set;
 *   <li>reject a selection OUTSIDE the set with {@code 403} — an authenticated caller may never
 *       reach a tenant it does not belong to, and the request must never leave the gateway;
 *   <li>keep a single-company login (scalar-era behavior) unchanged, including overwriting its
 *       spoofed {@code X-Company-Id} with a 403 when it names a foreign company.
 * </ul>
 *
 * Real Keycloak mints the tokens (the multivalued mapper is exercised for real).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCompanySelectionTest extends GatewayIntegrationTestBase {

  private static final String MULTI_USERNAME = "owner-multi";
  private static final String MULTI_PASSWORD = "multi-password";
  private static final String COMPANY_A = "22222222-2222-2222-2222-222222222222";
  private static final String COMPANY_B = "33333333-3333-3333-3333-333333333333";
  private static final String NON_MEMBER = "99999999-9999-9999-9999-999999999999";

  private String multiToken() throws Exception {
    return obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, MULTI_USERNAME, MULTI_PASSWORD);
  }

  @Test
  void noSelectionDefaultsToTheFirstAllowedCompany() throws Exception {
    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/income")
            .header(HttpHeaders.AUTHORIZATION, bearer(multiToken()))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(COMPANY_A);
  }

  @Test
  void aSelectionInTheAllowedSetIsHonoured() throws Exception {
    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/income")
            .header(HttpHeaders.AUTHORIZATION, bearer(multiToken()))
            .header("X-Company-Id", COMPANY_B)
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getHeader("X-Company-Id")).isEqualTo(COMPANY_B);
  }

  @Test
  void aSelectionOutsideTheAllowedSetIs403AndNeverReachesTheDownstream() throws Exception {
    String token = multiToken();
    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/statements/income")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .header("X-Company-Id", NON_MEMBER)
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
  void aSingleCompanyLoginStillForwardsItsOwnCompany() throws Exception {
    // owner-acme carries one company in the (now multivalued) claim — behavior unchanged.
    String token = obtainAccessToken();

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/statements/income")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getHeader("X-Company-Id")).isEqualTo(EXPECTED_COMPANY_ID);
  }

  @Test
  void aCashierCanBootstrapItsSessionFromCompaniesMine() throws Exception {
    // The console bootstraps EVERY persona's session from GET /companies/mine (ADR 0021). The
    // general /companies/** route is dashboard-only, so without the dedicated ME_ROLES route the
    // cashier would 403 here, get an empty session, and be locked out of the POS — the exact
    // regression this test pins down.
    String token =
        obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, "cashier-acme", "cashier-password");

    String response =
        gatewayClient()
            .get()
            .uri("/api/v1/companies/mine")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    assertThat(theForwardedRequest().getHeader("X-Company-Id"))
        .isEqualTo("11111111-1111-1111-1111-111111111111");
  }

  @Test
  void aSingleCompanyLoginSpoofingAForeignCompanyIs403() throws Exception {
    String token = obtainAccessToken();

    assertThatThrownBy(
            () ->
                gatewayClient()
                    .get()
                    .uri("/api/v1/statements/income")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .header("X-Company-Id", NON_MEMBER)
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(receivedRequests).isEmpty();
  }
}
