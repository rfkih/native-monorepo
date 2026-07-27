package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * End-to-end acceptance test for the public sign-up endpoint.
 *
 * <p>Runs under the {@code secured} profile so the REAL {@link
 * id.co.nativeapp.security.JwtSecurityConfig} + {@link
 * id.co.nativeapp.security.TenantBindingFilter} from {@code libs/security} govern — the sign-up
 * path must be {@code permitAll} at three points:
 *
 * <ol>
 *   <li>the security chain ({@code permitAll} via {@code native.security.public-paths}),
 *   <li>{@link id.co.nativeapp.security.TenantBindingFilter#shouldNotFilter} (no JWT → no 401),
 *   <li>{@link id.co.nativeapp.org.config.DevTenantFilter#shouldNotFilter} (skipped in non-dev
 *       anyway, but verified exempt).
 * </ol>
 *
 * <p>Asserts:
 *
 * <ul>
 *   <li>A valid sign-up request with a fresh email → {@code 201} with {@code companyId} in the
 *       body.
 *   <li>The Keycloak user exists with the correct {@code company_id} attribute and the {@code
 *       owner} realm role.
 *   <li>The company row exists in the database (via the admin/BYPASSRLS connection).
 *   <li>A second sign-up with the SAME email → {@code 409 Conflict}.
 *   <li>A sign-up with a missing required field → {@code 400} (bean validation).
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured")
@Testcontainers
class SignupAcceptanceTest {

  private static final String REALM = "native";
  private static final String ADMIN_CLIENT_ID = "native-admin";
  private static final String ADMIN_CLIENT_SECRET = "native-admin-secret";
  private static final String APP_USER = "app_user";
  private static final String APP_PASSWORD = "app_secret";

  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFiles("/native-realm.json");

  @SuppressWarnings("resource")
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

  @BeforeEach
  void resetTables() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE company, org_unit, legal_employer, user_outlet_assignment, outbox");
    } catch (SQLException ignored) {
      // Tables not created yet (first run) — nothing to reset.
    }
    // Also delete any Keycloak users created by previous tests (other than the seeded ones).
    // We cannot reset Keycloak's DB cheaply, so tests use unique email addresses per run to
    // avoid cross-test interference (the uniqueEmail() helper appends a nanotime suffix).
  }

  @DynamicPropertySource
  static void wireProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    String issuer = KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM;
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> issuer + "/protocol/openid-connect/certs");

    // Point the Keycloak admin client at the Testcontainers instance.
    registry.add("native.keycloak-admin.base-url", KEYCLOAK::getAuthServerUrl);
    registry.add("native.keycloak-admin.realm", () -> REALM);
    registry.add("native.keycloak-admin.client-id", () -> ADMIN_CLIENT_ID);
    registry.add("native.keycloak-admin.client-secret", () -> ADMIN_CLIENT_SECRET);
  }

  // ---------------------------------------------------------------------------
  // Happy-path
  // ---------------------------------------------------------------------------

  @Test
  void aValidSignupRequestReturns201WithCompanyId() throws Exception {
    String email = uniqueEmail();
    String responseBody = callSignup(signupBody(email));

    JsonNode node = JSON.readValue(responseBody, JsonNode.class);
    assertThat(node.has("companyId")).isTrue();
    assertThat(node.get("companyId").asString()).isNotBlank();
    assertThat(node.get("ownerEmail").asString()).isEqualTo(email);
    // The test realm has require-email-verification unset (default false).
    assertThat(node.get("emailVerificationRequired").asBoolean()).isFalse();

    // Company row was persisted.
    assertThat(rowCountAsAdmin("company")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("org_unit")).isEqualTo(1L);

    // Keycloak user has the correct company_id attribute and the owner role.
    String companyId = node.get("companyId").asString();
    verifyKeycloakUser(email, companyId);
  }

  @Test
  void signupWithoutATokenIsPermitted() throws Exception {
    // The endpoint must be reachable with NO Authorization header.
    String email = uniqueEmail();
    String responseBody = callSignup(signupBody(email));
    assertThat(responseBody).contains("companyId");
  }

  // ---------------------------------------------------------------------------
  // Duplicate email → 409
  // ---------------------------------------------------------------------------

  @Test
  void aSecondSignupWithTheSameEmailReturns409() throws Exception {
    String email = uniqueEmail();
    callSignup(signupBody(email)); // first signup succeeds

    assertThatThrownBy(() -> callSignup(signupBody(email)))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getResponseHeaders().getContentType())
                  .isNotNull()
                  .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/email-already-exists");
            });
  }

  // ---------------------------------------------------------------------------
  // Validation → 400
  // ---------------------------------------------------------------------------

  @Test
  void aSignupRequestWithAMissingFieldReturns400() {
    // ownerEmail is blank — bean validation must reject this before any service logic.
    String missingEmail =
        """
        {"companyName":"Acme","baseCurrency":"IDR","defaultLanguage":"id",
         "firstBusinessName":"Main","firstBusinessType":"outlet",
         "ownerEmail":"","ownerPassword":"secret","termsAccepted":true}
        """;
    assertBadRequest(missingEmail);
  }

  @Test
  void anUnsupportedCurrencyReturns400() {
    // EUR is a real ISO-4217 code but NOT platform-supported — the server-side whitelist must
    // reject it; a direct API call bypasses the client's option list.
    assertBadRequest(signupBody(uniqueEmail()).replace("\"IDR\"", "\"EUR\""));
  }

  @Test
  void anUnsupportedLanguageReturns400() {
    assertBadRequest(signupBody(uniqueEmail()).replace("\"id\"", "\"xx\""));
  }

  @Test
  void anUnsupportedBusinessTypeReturns400() {
    assertBadRequest(signupBody(uniqueEmail()).replace("\"outlet\"", "\"franchise\""));
  }

  @Test
  void aSignupBodyMissingTheTermsFieldEntirelyReturns400() {
    // A body with termsAccepted ABSENT (not just false). Jackson 3 fails null-into-primitive by
    // default, so with a primitive boolean this 500'd via the catch-all; the Boolean wrapper +
    // @NotNull must turn it into a clean 400 (regression: found live, 2026-07-26).
    assertBadRequest(signupBody(uniqueEmail()).replace(",\n  \"termsAccepted\": true", ""));
  }

  @Test
  void aSignupWithoutAcceptedTermsReturns400() {
    // Consent is validated server-side (@AssertTrue) — unchecked terms cannot be bypassed by
    // calling the API directly.
    assertBadRequest(
        signupBody(uniqueEmail()).replace("\"termsAccepted\": true", "\"termsAccepted\": false"));

    // No side effects: the rejected signup must not have created a company row.
    try {
      assertThat(rowCountAsAdmin("company")).isZero();
    } catch (SQLException ignored) {
      // Tables not created yet — equally proves nothing was persisted.
    }
  }

  private void assertBadRequest(String body) {
    assertThatThrownBy(() -> callSignup(body))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private String callSignup(String body) throws Exception {
    return appClient()
        .post()
        .uri("/api/v1/signup")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(String.class);
  }

  private static String signupBody(String email) {
    return """
        {
          "companyName": "Acme Corp",
          "baseCurrency": "IDR",
          "defaultLanguage": "id",
          "firstBusinessName": "Main Outlet",
          "firstBusinessType": "outlet",
          "ownerEmail": "%s",
          "ownerPassword": "secret-password-123",
          "termsAccepted": true
        }
        """
        .formatted(email);
  }

  /** Verifies the Keycloak user for {@code email} has the correct {@code company_id} attribute. */
  private void verifyKeycloakUser(String email, String expectedCompanyId) throws IOException {
    String adminToken = obtainAdminToken();
    String usersUrl =
        KEYCLOAK.getAuthServerUrl()
            + "/admin/realms/"
            + REALM
            + "/users?email="
            + encode(email)
            + "&exact=true";

    Request request =
        new Request.Builder()
            .url(usersUrl)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();

    try (Response response = HTTP.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      JsonNode users = JSON.readValue(response.body().string(), JsonNode.class);
      assertThat(users.isArray()).isTrue();
      assertThat(users.size()).isGreaterThanOrEqualTo(1);

      JsonNode user = users.get(0);
      JsonNode companyIds = user.path("attributes").path("company_id");
      assertThat(companyIds.isArray()).isTrue();
      assertThat(companyIds.get(0).asString()).isEqualTo(expectedCompanyId);

      // Signed-up owners start unverified (verification is enforced when the realm requires it),
      // and the ToS consent instant is recorded as a user attribute.
      assertThat(user.path("emailVerified").asBoolean()).isFalse();
      JsonNode termsAcceptedAt = user.path("attributes").path("terms_accepted_at");
      assertThat(termsAcceptedAt.isArray()).isTrue();
      assertThat(termsAcceptedAt.get(0).asString()).isNotBlank();
    }
  }

  private String obtainAdminToken() throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";

    String form =
        "grant_type=client_credentials"
            + "&client_id="
            + ADMIN_CLIENT_ID
            + "&client_secret="
            + ADMIN_CLIENT_SECRET;

    Request request =
        new Request.Builder()
            .url(tokenUrl)
            .post(
                RequestBody.create(
                    form, okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
            .build();

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
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(
            status -> status.is4xxClientError() || status.is5xxServerError(),
            (req, resp) -> {
              throw HttpClientErrorException.create(
                  resp.getStatusCode(),
                  resp.getStatusText(),
                  resp.getHeaders(),
                  resp.getBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
            })
        .build();
  }

  /** Returns a unique email address that does not collide across test runs. */
  private static String uniqueEmail() {
    return "signup-test-" + System.nanoTime() + "@example.co.id";
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
