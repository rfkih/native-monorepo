package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.security.app.TestSecuredApplication;
import id.co.nativeapp.security.app.WidgetController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The #16 defense-in-depth proof: a business service that depends only on {@code libs/security} (no
 * copied security code) validates the inbound RS256 JWT <em>locally</em> against Keycloak's JWKS
 * and binds the tenant from the verified {@code company_id} claim — so a forged {@code
 * X-Company-Id} sent straight to the service port can no longer bind an arbitrary tenant and defeat
 * RLS.
 *
 * <p>Boots the secured {@link TestSecuredApplication} on a random port under the NON-dev (default)
 * profile, against a real Keycloak + a real RLS-enforcing PostgreSQL (see {@link
 * SecurityProofTestBase}). Four proofs:
 *
 * <ul>
 *   <li>(a) no bearer token -> {@code 401}, the handler is never invoked;
 *   <li>(b) a forged {@code X-Company-Id} header but NO valid JWT -> {@code 401} (the header is
 *       ignored, no tenant is bound, the handler is never invoked);
 *   <li>(c) a real RS256 token binds {@code TenantContext} from its {@code company_id} claim, so
 *       the RLS-scoped read returns ONLY that tenant's widget;
 *   <li>(c2) a spoofed {@code X-Company-Id} naming a tenant OUTSIDE the token's set -> {@code 403}
 *       ({@code invalid-company-selection}, ADR 0021 — rejected outright, never silently replaced);
 *   <li>(d) a valid token with NO {@code company_id} claim -> {@code 403} (RFC-7807); and
 *   <li>(e) a MULTI-company token (claim = [A, B]) defaults to A, and an {@code X-Company-Id}
 *       selection of B re-binds RLS to B — the token-validated company switch, end to end.
 * </ul>
 */
@SpringBootTest(
    classes = TestSecuredApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalJwtValidationDefenseInDepthTest extends SecurityProofTestBase {

  private static final String WIDGETS = "/api/v1/widgets";

  @BeforeEach
  void resetInvocationCounter() {
    WidgetController.INVOCATIONS.set(0);
  }

  @Test
  void aRequestWithNoBearerTokenIsRejectedWith401AndNeverReachesTheHandler() {
    assertThatThrownBy(() -> appClient().get().uri(WIDGETS).retrieve().body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThat(WidgetController.INVOCATIONS.get()).isZero();
  }

  @Test
  void aForgedCompanyIdHeaderWithoutAValidJwtIsRejectedWith401AndBindsNoTenant() {
    // The classic bypass: hit the service port directly with a spoofed tenant header and NO token.
    // Without a valid JWT the security chain rejects with 401, the header is ignored, and no tenant
    // is bound — the very gap #16 closes.
    assertThatThrownBy(
            () ->
                appClient()
                    .get()
                    .uri(WIDGETS)
                    .header("X-Company-Id", TENANT_B)
                    .header("X-Actor", "attacker")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThat(WidgetController.INVOCATIONS.get()).isZero();
  }

  @Test
  void aValidTokenBindsTheTenantFromTheClaimSoTheRlsScopedReadReturnsOnlyThatTenantsData()
      throws Exception {
    String token = tokenWithTenant();

    @SuppressWarnings("unchecked")
    List<String> names =
        appClient()
            .get()
            .uri(WIDGETS)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(List.class);

    // RLS scoped the read to TENANT_A (the claim).
    assertThat(names).containsExactly("Tenant A Widget");
    assertThat(names).doesNotContain("Tenant B Widget");
    assertThat(WidgetController.INVOCATIONS.get()).isEqualTo(1);
  }

  @Test
  void aSpoofedCompanyIdOutsideTheTokensSetIsRejectedWith403AndNeverReachesTheHandler()
      throws Exception {
    // owner-acme's token authorizes ONLY TENANT_A; naming TENANT_B is an invalid selection — it is
    // rejected outright (never silently replaced with the claim value), so a forged header can
    // never bind a foreign tenant NOR be quietly ignored (ADR 0021).
    String token = tokenWithTenant();

    assertThatThrownBy(
            () ->
                appClient()
                    .get()
                    .uri(WIDGETS)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .header("X-Company-Id", TENANT_B)
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/invalid-company-selection");
            });

    assertThat(WidgetController.INVOCATIONS.get()).isZero();
  }

  @Test
  void aMultiCompanyTokenDefaultsToItsFirstCompanyAndSwitchesOnAValidatedSelection()
      throws Exception {
    // owner-multi's claim carries [TENANT_A, TENANT_B]. No selection → the first (A) binds.
    String token = tokenWithBothTenants();

    @SuppressWarnings("unchecked")
    List<String> defaulted =
        appClient()
            .get()
            .uri(WIDGETS)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve()
            .body(List.class);
    assertThat(defaulted).containsExactly("Tenant A Widget");

    // Selecting TENANT_B (in the token's set) re-binds the tenant — RLS now returns B's widget.
    @SuppressWarnings("unchecked")
    List<String> switched =
        appClient()
            .get()
            .uri(WIDGETS)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .header("X-Company-Id", TENANT_B)
            .retrieve()
            .body(List.class);
    assertThat(switched).containsExactly("Tenant B Widget");
    assertThat(switched).doesNotContain("Tenant A Widget");
  }

  @Test
  void aValidTokenWithoutACompanyIdClaimIsRejectedWith403AndNeverReachesTheHandler()
      throws Exception {
    String tenantless = tokenWithoutTenant();

    assertThatThrownBy(
            () ->
                appClient()
                    .get()
                    .uri(WIDGETS)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenantless))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              // Authentic token, no tenant -> 403 tenant/authZ denial (RFC-7807), not 401.
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseHeaders().getContentType())
                  .isNotNull()
                  .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/missing-tenant-claim");
            });

    assertThat(WidgetController.INVOCATIONS.get()).isZero();
  }
}
