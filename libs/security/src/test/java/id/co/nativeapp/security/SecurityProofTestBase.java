package id.co.nativeapp.security;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared container fixture for the #16 defense-in-depth proof. Stands up the real collaborators a
 * secured service depends on:
 *
 * <ul>
 *   <li><strong>Keycloak</strong> (dasniko Testcontainers) importing {@code native-realm.json} — it
 *       issues genuine RS256 tokens, so the in-service signature/issuer/JWKS validation and the
 *       {@code company_id}-claim tenant binding are exercised end to end, not mocked; and
 *   <li><strong>PostgreSQL 16</strong> as a NON-superuser ({@code app_user}, no BYPASSRLS) so the
 *       RLS policy genuinely engages — the read returns only the bound tenant's rows.
 * </ul>
 *
 * <p>The app under test ({@link id.co.nativeapp.security.app.TestSecuredApplication}) adds NO
 * security code; the JWT validation + tenant binding come solely from {@code libs/security}'s
 * auto-configuration on the classpath.
 */
@Testcontainers
abstract class SecurityProofTestBase {

  protected static final String REALM = "native";
  protected static final String CLIENT_ID = "native-gateway";
  protected static final String CLIENT_SECRET = "native-gateway-secret";

  // A user WITH a company_id attribute -> token carries the tenant claim.
  protected static final String USERNAME = "owner-acme";
  protected static final String PASSWORD = "owner-password";
  protected static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  // A second tenant that owns a widget too — used to prove RLS hides it from tenant A.
  protected static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

  // A valid user WITHOUT a company_id attribute -> authentic but tenant-less token (-> 403).
  protected static final String TENANTLESS_USERNAME = "tenantless-user";
  protected static final String TENANTLESS_PASSWORD = "tenantless-password";

  static final String APP_USER = "app_user";
  static final String APP_PASSWORD = "app_secret";

  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFiles("/native-realm.json");

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private static final OkHttpClient HTTP = new OkHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort protected int port;

  /**
   * Singleton-container start: Keycloak and Postgres are started once for the whole JVM and reaped
   * by Ryuk at JVM exit. The app role is provisioned before Flyway runs (the dynamic datasource
   * points at it), so Flyway and every repository call run as the unprivileged role.
   */
  @BeforeAll
  static synchronized void startContainers() {
    KEYCLOAK.start();
    POSTGRES.start();
    provisionAppRole();
  }

  /** Reset the widget table to a known two-tenant seed before each test. */
  @BeforeEach
  void seedWidgets() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE widget");
      insertWidget(admin, "Tenant A Widget", TENANT_A);
      insertWidget(admin, "Tenant B Widget", TENANT_B);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to seed widgets", e);
    }
  }

  private static void insertWidget(Connection admin, String name, String companyId)
      throws SQLException {
    try (var ps =
        admin.prepareStatement(
            "INSERT INTO widget"
                + " (id, name, created_at, created_by, updated_at, updated_by, version, company_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, 0, ?)")) {
      Instant now = Instant.now();
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, name);
      ps.setObject(3, java.sql.Timestamp.from(now));
      ps.setString(4, "seed");
      ps.setObject(5, java.sql.Timestamp.from(now));
      ps.setString(6, "seed");
      ps.setString(7, companyId);
      ps.executeUpdate();
    }
  }

  private static Connection adminConnection() throws SQLException {
    // The admin (superuser, implicit BYPASSRLS) connection seeds rows of every tenant regardless of
    // any session GUC.
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

  @DynamicPropertySource
  static void wireProperties(DynamicPropertyRegistry registry) {
    // The app connects as the unprivileged app role (Flyway runs as it, owning the table) so RLS
    // genuinely engages.
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    String issuer = KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM;
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> issuer + "/protocol/openid-connect/certs");
  }

  /** A real RS256 token for {@code owner-acme} (carries the {@code company_id} claim + roles). */
  protected static String tokenWithTenant() throws IOException {
    return obtainAccessToken(CLIENT_ID, CLIENT_SECRET, USERNAME, PASSWORD);
  }

  /** A real RS256 token for {@code tenantless-user} (authentic but no {@code company_id} claim). */
  protected static String tokenWithoutTenant() throws IOException {
    return obtainAccessToken(CLIENT_ID, CLIENT_SECRET, TENANTLESS_USERNAME, TENANTLESS_PASSWORD);
  }

  private static String obtainAccessToken(
      String clientId, String clientSecret, String username, String password) throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";
    FormBody form =
        new FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("username", username)
            .add("password", password)
            .build();
    Request request = new Request.Builder().url(tokenUrl).post(form).build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode body = JSON.readValue(response.body().string(), JsonNode.class);
      return body.get("access_token").asString();
    }
  }

  protected RestClient appClient() {
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }

  protected static String bearer(String token) {
    return "Bearer " + token;
  }
}
