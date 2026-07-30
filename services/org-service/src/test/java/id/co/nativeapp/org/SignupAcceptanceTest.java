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

    // Company row was persisted (root business unit + its seeded default outlet, ADR 0012).
    assertThat(rowCountAsAdmin("company")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("org_unit")).isEqualTo(2L);

    // Country-derived defaults + funnel fields landed on the company row (ADR 0025).
    String companyId = node.get("companyId").asString();
    assertThat(companyColumnAsAdmin(companyId, "country")).isEqualTo("ID");
    assertThat(companyColumnAsAdmin(companyId, "base_currency")).isEqualTo("IDR");
    assertThat(companyColumnAsAdmin(companyId, "phone")).isEqualTo("+62 812 3456 7890");
    assertThat(companyColumnAsAdmin(companyId, "company_size")).isEqualTo("1-5");
    assertThat(companyColumnAsAdmin(companyId, "primary_interest")).isEqualTo("own-company");

    // Keycloak user has the correct company_id attribute, the owner role, and the owner's name.
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
        {"companyName":"Acme","country":"ID","defaultLanguage":"id",
         "firstBusinessName":"Main","vertical":"restaurant",
         "ownerFirstName":"Budi","companySize":"1-5","primaryInterest":"own-company",
         "ownerEmail":"","ownerPassword":"secret","termsAccepted":true}
        """;
    assertBadRequest(missingEmail);
  }

  @Test
  void aClientSuppliedBaseCurrencyIsIgnoredAndDerivationWins() throws Exception {
    // ADR 0025: baseCurrency left the wire contract — the server derives it from country. An OLD
    // client still sending one (here even an unsupported EUR) is ignored by deserialization, not
    // rejected, and the country-derived currency wins.
    String email = uniqueEmail();
    String legacyBody =
        signupBody(email)
            .replace("\"country\": \"ID\",", "\"country\": \"ID\",\n  \"baseCurrency\": \"EUR\",");
    String responseBody = callSignup(legacyBody);
    JsonNode node = JSON.readValue(responseBody, JsonNode.class);
    assertThat(companyColumnAsAdmin(node.get("companyId").asString(), "base_currency"))
        .isEqualTo("IDR");
  }

  @Test
  void countryIdDerivesIdrBooks() throws Exception {
    String responseBody = callSignup(signupBody(uniqueEmail()));
    String companyId = JSON.readValue(responseBody, JsonNode.class).get("companyId").asString();
    assertThat(companyColumnAsAdmin(companyId, "country")).isEqualTo("ID");
    assertThat(companyColumnAsAdmin(companyId, "base_currency")).isEqualTo("IDR");
  }

  @Test
  void countryUsDerivesUsdBooks() throws Exception {
    String body = signupBody(uniqueEmail()).replace("\"country\": \"ID\"", "\"country\": \"US\"");
    String responseBody = callSignup(body);
    String companyId = JSON.readValue(responseBody, JsonNode.class).get("companyId").asString();
    assertThat(companyColumnAsAdmin(companyId, "country")).isEqualTo("US");
    assertThat(companyColumnAsAdmin(companyId, "base_currency")).isEqualTo("USD");
  }

  @Test
  void anUnknownCountryReturns400WithNoResidue() throws Exception {
    // "XX" passes the [A-Z]{2} pattern but is not a real ISO 3166-1 country — the domain check
    // must 400 it BEFORE the Keycloak user is created (derivation runs first), so a validation
    // failure never spends a compensation: no company row AND no Keycloak user may exist.
    String email = uniqueEmail();
    assertBadRequest(signupBody(email).replace("\"country\": \"ID\"", "\"country\": \"XX\""));
    try {
      assertThat(rowCountAsAdmin("company")).isZero();
    } catch (SQLException ignored) {
      // Tables not created yet — equally proves nothing was persisted.
    }
    assertThat(keycloakUsersFor(email).size()).isZero();
  }

  @Test
  void aLowercaseCountryReturns400() {
    // The wire contract is strict alpha-2 upper-case; normalization is a domain courtesy for the
    // service layer, not a request-format leniency.
    assertBadRequest(
        signupBody(uniqueEmail()).replace("\"country\": \"ID\"", "\"country\": \"id\""));
  }

  @Test
  void aBlankCompanySizeReturns400() {
    assertBadRequest(
        signupBody(uniqueEmail()).replace("\"companySize\": \"1-5\",", "\"companySize\": \"\","));
  }

  @Test
  void anUnknownPrimaryInterestReturns400() {
    assertBadRequest(signupBody(uniqueEmail()).replace("\"own-company\"", "\"world-domination\""));
  }

  @Test
  void anInvalidPhoneReturns400() {
    assertBadRequest(
        signupBody(uniqueEmail())
            .replace("\"phone\": \"+62 812 3456 7890\"", "\"phone\": \"not-a-phone\""));
  }

  @Test
  void aMononymOwnerWithoutPhoneSignsUpSuccessfully() throws Exception {
    // Indonesian mononyms: ownerLastName is optional, as is phone. The Keycloak user gets a
    // firstName and NO lastName.
    String email = uniqueEmail();
    String body =
        signupBody(email)
            .replace("\"ownerLastName\": \"Santoso\",\n  ", "")
            .replace("\"phone\": \"+62 812 3456 7890\",\n  ", "");
    String responseBody = callSignup(body);
    assertThat(responseBody).contains("companyId");

    JsonNode user = keycloakUsersFor(email).get(0);
    assertThat(user.path("firstName").asString()).isEqualTo("Budi");
    // lastName was never sent — the representation must not carry one (absent or empty; it must
    // certainly not contain a value).
    assertThat(user.toString()).doesNotContain("Santoso");
  }

  @Test
  void anUnsupportedLanguageReturns400() {
    assertBadRequest(signupBody(uniqueEmail()).replace("\"id\"", "\"xx\""));
  }

  @Test
  void anUnknownFirstBusinessTypePropertyIsIgnoredNotRejected() throws Exception {
    // ADR 0012 removed the business-type choice from the wire. An OLD request body still
    // carrying firstBusinessType must be accepted (unknown JSON properties are ignored by
    // deserialization) — no compat shim, no 400.
    String legacyBody =
        signupBody(uniqueEmail())
            .replace(
                "\"firstBusinessName\": \"Main Outlet\",",
                "\"firstBusinessName\": \"Main Outlet\",\n  \"firstBusinessType\": \"branch\",");
    String responseBody = callSignup(legacyBody);
    assertThat(responseBody).contains("companyId");
  }

  @Test
  void aSignupWithoutAVerticalReturns400() {
    assertBadRequest(signupBody(uniqueEmail()).replace("\"vertical\": \"restaurant\",", ""));
  }

  @Test
  void anUnsupportedVerticalReturns400() {
    // laundromat exists in entitlement's module catalog but is NOT a whitelisted BU vertical —
    // the server-side whitelist is authoritative (a direct API call bypasses the client picker).
    assertBadRequest(
        signupBody(uniqueEmail())
            .replace("\"vertical\": \"restaurant\"", "\"vertical\": \"laundromat\""));
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
    // NOTE: several tests surgically .replace(...) exact literals from this body (including the
    // ",\n  \"termsAccepted\": true" tail) — keep the formatting stable when editing. There is
    // deliberately NO baseCurrency: it is derived server-side from country (ADR 0025).
    return """
        {
          "companyName": "Acme Corp",
          "country": "ID",
          "defaultLanguage": "id",
          "firstBusinessName": "Main Outlet",
          "vertical": "restaurant",
          "ownerFirstName": "Budi",
          "ownerLastName": "Santoso",
          "phone": "+62 812 3456 7890",
          "companySize": "1-5",
          "primaryInterest": "own-company",
          "ownerEmail": "%s",
          "ownerPassword": "secret-password-123",
          "termsAccepted": true
        }
        """
        .formatted(email);
  }

  /** Verifies the Keycloak user for {@code email} has the correct attributes, role and name. */
  private void verifyKeycloakUser(String email, String expectedCompanyId) throws IOException {
    JsonNode users = keycloakUsersFor(email);
    assertThat(users.size()).isGreaterThanOrEqualTo(1);

    JsonNode user = users.get(0);
    JsonNode companyIds = user.path("attributes").path("company_id");
    assertThat(companyIds.isArray()).isTrue();
    assertThat(companyIds.get(0).asString()).isEqualTo(expectedCompanyId);

    // The owner's name is stored on Keycloak's NATIVE firstName/lastName fields (ADR 0025).
    assertThat(user.path("firstName").asString()).isEqualTo("Budi");
    assertThat(user.path("lastName").asString()).isEqualTo("Santoso");

    // Signed-up owners start unverified (verification is enforced when the realm requires it),
    // and the ToS consent instant is recorded as a user attribute.
    assertThat(user.path("emailVerified").asBoolean()).isFalse();
    JsonNode termsAcceptedAt = user.path("attributes").path("terms_accepted_at");
    assertThat(termsAcceptedAt.isArray()).isTrue();
    assertThat(termsAcceptedAt.get(0).asString()).isNotBlank();
  }

  /** Fetches the Keycloak user representations for {@code email} (exact match; may be empty). */
  private JsonNode keycloakUsersFor(String email) throws IOException {
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
      return users;
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

  /** Reads one column of the given company row via the admin/BYPASSRLS connection. */
  private String companyColumnAsAdmin(String companyId, String column) throws SQLException {
    try (Connection admin = adminConnection();
        var ps = admin.prepareStatement("SELECT " + column + " FROM company WHERE id = ?::uuid")) {
      ps.setString(1, companyId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("company row %s exists", companyId).isTrue();
        return rs.getString(1);
      }
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
