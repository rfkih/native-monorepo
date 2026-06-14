package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.security.app.BootstrapController;
import id.co.nativeapp.security.app.TestSecuredApplication;
import id.co.nativeapp.security.app.WidgetController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Proof for the tenant-OPTIONAL (bootstrap) exemption — the fix for the production regression where
 * {@code TenantBindingFilter} {@code 403}'d every tenant-less token, including the create-company
 * bootstrap (the owner creating their FIRST company has no {@code company_id} claim yet).
 *
 * <p>Boots the secured {@link TestSecuredApplication} under the NON-dev (default) profile against a
 * real Keycloak + RLS Postgres (see {@link SecurityProofTestBase}). {@code POST /api/v1/bootstrap}
 * is declared tenant-optional via {@code native.security.tenant-optional} in the test {@code
 * application.yml}; {@code GET /api/v1/widgets} is a normal tenant-scoped endpoint. Three proofs:
 *
 * <ul>
 *   <li>(a) a VALID but TENANT-LESS token reaches the tenant-optional handler ({@code 201}, NO
 *       {@code 403}) — and the controller opened its OWN tenant scope, no tenant was bound at the
 *       edge;
 *   <li>(b) the SAME tenant-less token on a normal tenant-scoped path is still {@code 403}
 *       (RFC-7807) — the exemption is narrow, not service-wide; and
 *   <li>(c) NO token on the tenant-optional path is still {@code 401} — authentication is still
 *       required, the handler is never reached.
 * </ul>
 */
@SpringBootTest(
    classes = TestSecuredApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantOptionalBootstrapTest extends SecurityProofTestBase {

  private static final String BOOTSTRAP = "/api/v1/bootstrap";
  private static final String WIDGETS = "/api/v1/widgets";

  @BeforeEach
  void resetInvocationCounters() {
    BootstrapController.INVOCATIONS.set(0);
    WidgetController.INVOCATIONS.set(0);
  }

  @Test
  void aValidButTenantlessTokenReachesTheBootstrapHandlerWith201AndNoTenantBoundAtTheEdge()
      throws Exception {
    String tenantless = tokenWithoutTenant();

    String body =
        appClient()
            .post()
            .uri(BOOTSTRAP)
            .header(HttpHeaders.AUTHORIZATION, bearer(tenantless))
            .retrieve()
            .body(String.class);

    // The handler ran (no 403), proving the tenant-optional exemption let the tenant-less token in.
    assertThat(BootstrapController.INVOCATIONS.get()).isEqualTo(1);
    // No tenant was bound at the edge for this bootstrap; the controller opened its own scope over
    // the freshly generated id — exactly how CompanyController.createCompany works.
    assertThat(body).contains("boundOnEntry=false");
    assertThat(body).contains("openedScope=");
  }

  @Test
  void theSameTenantlessTokenOnANormalTenantScopedPathIsStill403() throws Exception {
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
              // The exemption is endpoint-scoped: a tenant-less token on a tenant-scoped path is
              // still an authZ denial (403 RFC-7807), NOT relaxed by the bootstrap exemption.
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseHeaders().getContentType())
                  .isNotNull()
                  .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/missing-tenant-claim");
            });

    assertThat(WidgetController.INVOCATIONS.get()).isZero();
  }

  @Test
  void noTokenOnTheTenantOptionalPathIsStill401AndNeverReachesTheHandler() {
    // Tenant-optional relaxes ONLY the missing-company_id 403; authentication is still required, so
    // a request with no bearer token is rejected 401 by the security chain before the handler.
    assertThatThrownBy(() -> appClient().post().uri(BOOTSTRAP).retrieve().body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThat(BootstrapController.INVOCATIONS.get()).isZero();
  }
}
