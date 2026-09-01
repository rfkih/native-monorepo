package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SECURED (non-dev) proof for the tenant bootstrap — org-service's half of the production
 * regression fix. The rest of org-service's suite runs under the {@code dev} profile (the build's
 * {@code test} task sets {@code spring.profiles.active=dev}, which selects libs/security's
 * permissive {@code DevSecurityConfig} + header-trust {@code DevTenantFilter}); this class instead
 * runs under an explicit non-dev profile ({@code @ActiveProfiles("secured")}, which wins over the
 * build's system property because {@code @ActiveProfiles} sets the active profiles
 * programmatically), so the real {@code JwtSecurityConfig} + {@code TenantBindingFilter} from
 * libs/security govern.
 *
 * <p>It stands up a real Keycloak (genuine RS256 tokens) and a real RLS-enforcing PostgreSQL, then
 * proves both directions of the fix end to end through the running HTTP edge:
 *
 * <ul>
 *   <li><strong>bootstrap succeeds</strong> — {@code POST /api/v1/companies} with a valid OWNER
 *       token that carries NO {@code company_id} claim returns {@code 201} and persists the company
 *       (org-service declares this endpoint {@code native.security.tenant-optional}, so the
 *       tenant-less bootstrap token is NOT {@code 403}'d; the controller opens its own scope over
 *       the freshly generated id); and
 *   <li><strong>tenant-scoped stays strict</strong> — the SAME tenant-less token on a normal
 *       tenant-scoped endpoint ({@code POST /api/v1/companies/{id}/businesses}) is still {@code
 *       403} (RFC-7807), proving the exemption is endpoint-scoped, not service-wide.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured")
@Testcontainers
class SecuredCompanyBootstrapTest {

  private static final String REALM = "native";
  private static final String CLIENT_ID = "native-gateway";
  private static final String CLIENT_SECRET = "native-gateway-secret";

  // A valid OWNER user WITHOUT a company_id attribute -> authentic but tenant-less token. This is
  // the bootstrap actor: the owner creating their FIRST company, before any tenant exists.
  private static final String TENANTLESS_USERNAME = "tenantless-user";
  private static final String TENANTLESS_PASSWORD = "tenantless-password";

  private static final String APP_USER = "app_user";
  private static final String APP_PASSWORD = "app_secret";

  private static final String COMPANIES = "/api/v1/companies";

  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFiles("/native-realm.json");

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private static final OkHttpClient HTTP = new OkHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @BeforeAll
  static synchronized void startContainers() {
    KEYCLOAK.start();
    POSTGRES.start();
    provisionAppRole();
  }

  /** Start each test from empty tables (the unprivileged app role; admin truncate bypasses RLS). */
  @BeforeEach
  void resetTables() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE company, org_unit, legal_employer, user_outlet_assignment, outbox");
    } catch (SQLException ignored) {
      // Tables not created yet (pre-Flyway) — nothing to reset.
    }
  }

  @DynamicPropertySource
  static void wireProperties(DynamicPropertyRegistry registry) {
    // The app connects as the unprivileged app role (Flyway runs as it, owning the tables) so RLS
    // genuinely engages — the bootstrap insert must satisfy WITH CHECK under it.
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    // Point the non-dev resource-server config at the Testcontainers Keycloak (overrides the
    // application.yml localhost defaults); native.security.tenant-optional stays from
    // application.yml.
    String issuer = KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM;
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> issuer + "/protocol/openid-connect/certs");

    // The authenticated create now BINDS its creator (membership-first, ADR 0021) via the Keycloak
    // Admin API — point the admin client at the same Testcontainers Keycloak.
    registry.add("native.keycloak-admin.base-url", KEYCLOAK::getAuthServerUrl);
    registry.add("native.keycloak-admin.realm", () -> REALM);
    registry.add("native.keycloak-admin.client-id", () -> "native-admin");
    registry.add("native.keycloak-admin.client-secret", () -> "native-admin-secret");
  }

  @Test
  void bootstrapSucceedsWithAValidOwnerTokenThatHasNoCompanyIdClaim() throws Exception {
    String tenantless = tokenWithoutTenant();

    String body =
        appClient()
            .post()
            .uri(COMPANIES)
            .header(HttpHeaders.AUTHORIZATION, bearer(tenantless))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"name":"Warung Padang","baseCurrency":"IDR","defaultLanguage":"id",
                 "firstBusiness":{"name":"Main Outlet","vertical":"restaurant"}}
                """)
            .retrieve()
            .body(String.class);

    // 201 (not 403): the tenant-optional exemption let the tenant-less bootstrap token through, and
    // CompanyController opened its own scope so the RLS WITH CHECK passed on the bootstrap insert.
    assertThat(body).contains("\"baseCurrency\":\"IDR\"");
    assertThat(body).contains("\"defaultLanguage\":\"id\"");
    assertThat(rowCountAsAdmin("company")).isEqualTo(1L);
    // Root business unit + its seeded default outlet (ADR 0012).
    assertThat(rowCountAsAdmin("org_unit")).isEqualTo(1L);
  }

  @Test
  void aTenantlessTokenOnATenantScopedEndpointIsStill403() throws Exception {
    String tenantless = tokenWithoutTenant();
    UUID someCompany = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                appClient()
                    .post()
                    .uri(COMPANIES + "/" + someCompany + "/businesses")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenantless))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\":\"Second Outlet\",\"vertical\":\"restaurant\"}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              // The sub-resource is NOT tenant-optional: a tenant-less token is a 403 authZ denial
              // (RFC-7807), proving the bootstrap exemption is narrow, not service-wide.
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseHeaders().getContentType())
                  .isNotNull()
                  .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/missing-tenant-claim");
            });

    // Nothing was written: the request was rejected at the security edge, before any handler.
    assertThat(rowCountAsAdmin("org_unit")).isZero();
  }

  /** A real RS256 token for {@code tenantless-user} (authentic OWNER, no {@code company_id}). */
  private static String tokenWithoutTenant() throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";
    FormBody form =
        new FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("username", TENANTLESS_USERNAME)
            .add("password", TENANTLESS_PASSWORD)
            .build();
    Request request = new Request.Builder().url(tokenUrl).post(form).build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode node = JSON.readValue(response.body().string(), JsonNode.class);
      return node.get("access_token").asString();
    }
  }

  private long rowCountAsAdmin(String table) throws SQLException {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Connection adminConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void provisionAppRole() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "DO $$ BEGIN "
              + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + APP_USER
              + "') THEN "
              + "CREATE ROLE "
              + APP_USER
              + " LOGIN PASSWORD '"
              + APP_PASSWORD
              + "'; "
              + "END IF; END $$");
      st.execute("GRANT ALL ON SCHEMA public TO " + APP_USER);
      st.execute("GRANT ALL ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_USER);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to provision the app_user role", e);
    }
  }

  private RestClient appClient() {
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
