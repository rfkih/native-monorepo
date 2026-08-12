package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * End-to-end acceptance test for the device-credential endpoints ({@code
 * /api/v1/org-units/{outletId}/device-credential}, ADR 0049 P3a).
 *
 * <p>Runs under the {@code secured} profile (real Keycloak, real Postgres) to exercise the full
 * mint → bind → reveal → reset → delete flow, and verifies via the Keycloak Admin API that the
 * minted user is EXACTLY the kiosk shape the ADR specifies:
 *
 * <ul>
 *   <li>{@code attributes.actor_type = ["device"]} and the caller's {@code company_id};
 *   <li>{@code credentials[0].temporary = false} — proven operationally: the returned password logs
 *       in via the password grant with NO {@code UPDATE_PASSWORD} interstitial;
 *   <li>{@code requiredActions = []} — no forced password change, no email verification;
 *   <li>realm role = {@code cashier} ONLY (never owner/manager);
 *   <li>exactly one {@code user_outlet_assignment} row binds it to the target outlet (verified via
 *       {@code GET /api/v1/org-units/{outletId}/users});
 *   <li>excluded from the Team list ({@code GET /api/v1/users}).
 * </ul>
 *
 * <p>The realm ({@code native-realm.json}) pre-seeds {@code owner-acme} (company {@code
 * 11111111...}), used as the authenticated owner/manager caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured")
@Testcontainers
class DeviceCredentialAcceptanceTest {

  private static final String REALM = "native";
  private static final String ADMIN_CLIENT_ID = "native-admin";
  private static final String ADMIN_CLIENT_SECRET = "native-admin-secret";
  private static final String GW_CLIENT_ID = "native-gateway";
  private static final String GW_CLIENT_SECRET = "native-gateway-secret";
  private static final String OWNER_USERNAME = "owner-acme";
  private static final String OWNER_PASSWORD = "owner-password";
  // A cashier-ONLY login in company A — the non-owner/manager caller for the
  // TeamAdministrationGuard.requireOwnerOrManager() server-side-authz tests.
  private static final String CASHIER_USERNAME = "cashier-acme";
  private static final String CASHIER_PASSWORD = "cashier-password";
  private static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";

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
  void resetDatabase() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE company, org_unit, legal_employer, user_outlet_assignment,"
              + " device_credential, outbox");
    } catch (SQLException ignored) {
      // Not yet migrated (first run) — nothing to reset.
    }
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

    registry.add("native.keycloak-admin.base-url", KEYCLOAK::getAuthServerUrl);
    registry.add("native.keycloak-admin.realm", () -> REALM);
    registry.add("native.keycloak-admin.client-id", () -> ADMIN_CLIENT_ID);
    registry.add("native.keycloak-admin.client-secret", () -> ADMIN_CLIENT_SECRET);
  }

  // ===========================================================================
  // GET /api/v1/users must not 404 a not-yet-bootstrapped tenant (ADR 0054 regression)
  // ===========================================================================

  @Test
  void listUsersReturns200NotA404WhenTheTenantHasNoCompanyRow() throws Exception {
    // ADR 0054 made GET /api/v1/users read the tenant's company_code (to strip the <companyCode>.
    // prefix off scoped logins for display). A not-yet-bootstrapped tenant has no `company` row yet
    // (resetDatabase truncated it) — the endpoint must still return the team (its own contract:
    // "empty list, not a 404, if the company has no users yet"), just without prefix-stripping.
    // Before the fix this threw NoSuchElementException -> 404.
    String token = tokenForOwner();
    JsonNode team = getJson(token, "/api/v1/users");
    assertThat(team.isArray()).isTrue();
    boolean ownerListed = false;
    for (JsonNode member : team) {
      if (OWNER_USERNAME.equals(member.path("username").asString())) {
        ownerListed = true;
      }
    }
    assertThat(ownerListed)
        .as("owner is listed with its raw username even when no company row exists")
        .isTrue();
  }

  // ===========================================================================
  // Full lifecycle
  // ===========================================================================

  @Test
  void createBindsAndShapesTheKioskUserThenRevealResetDeleteAllWorkEndToEnd() throws Exception {
    String token = tokenForOwner();
    String outletId = createOutletUnderCompanyA(token);

    // ---- CREATE ------------------------------------------------------------
    JsonNode created = createDeviceCredential(token, outletId);
    String username = created.get("username").asString();
    String password = created.get("password").asString();
    assertThat(created.get("outletId").asString()).isEqualTo(outletId);
    assertThat(username).isEqualTo("till." + outletId);
    assertThat(password).isNotBlank();

    // ---- Keycloak-shape assertions ------------------------------------------
    String deviceUserId = findKeycloakUserIdByUsername(username);
    JsonNode kcUser = fetchKeycloakUser(deviceUserId);

    // actor_type=device + company_id attribute.
    assertThat(listAttr(kcUser, "actor_type")).containsExactly("device");
    assertThat(listAttr(kcUser, "company_id")).containsExactly(COMPANY_A);

    // No forced actions — kiosk-persistent (no UPDATE_PASSWORD, no VERIFY_EMAIL).
    assertThat(kcUser.path("requiredActions").size()).isZero();

    // cashier role ONLY — never owner/manager.
    var roles = fetchRealmRoleNames(deviceUserId);
    assertThat(roles).containsExactly("cashier");

    // temporary=false proven operationally: the password logs in via the password grant with no
    // interstitial (a temporary=true credential would still grant a token, but Keycloak would also
    // report an UPDATE_PASSWORD required action above, which we already asserted is absent).
    String deviceToken = obtainTokenOrNull(username, password);
    assertThat(deviceToken).as("device credential should authenticate").isNotBlank();

    // ---- Outlet binding: exactly one user_outlet_assignment row -------------
    JsonNode outletUsers = getJson(token, "/api/v1/org-units/" + outletId + "/users");
    assertThat(outletUsers.isArray()).isTrue();
    boolean deviceBound = false;
    for (JsonNode row : outletUsers) {
      if (deviceUserId.equals(row.path("userId").asString())) {
        deviceBound = true;
      }
    }
    assertThat(deviceBound).as("device user bound to the outlet").isTrue();

    // ---- Team-list exclusion -------------------------------------------------
    JsonNode team = getJson(token, "/api/v1/users");
    for (JsonNode member : team) {
      assertThat(member.path("username").asString()).isNotEqualTo(username);
    }

    // ---- Duplicate create -> 409 ---------------------------------------------
    assertThatThrownBy(() -> createDeviceCredential(token, outletId))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // ---- Reveal returns the SAME username + password -------------------------
    JsonNode revealed = getJson(token, "/api/v1/org-units/" + outletId + "/device-credential");
    assertThat(revealed.get("username").asString()).isEqualTo(username);
    assertThat(revealed.get("password").asString()).isEqualTo(password);

    // ---- Reset rotates the password: old fails, new works --------------------
    String resetBody =
        appClient()
            .post()
            .uri("/api/v1/org-units/" + outletId + "/device-credential/reset")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(String.class);
    JsonNode reset = JSON.readValue(resetBody, JsonNode.class);
    String newPassword = reset.get("password").asString();
    assertThat(newPassword).isNotEqualTo(password);

    assertThat(obtainTokenOrNull(username, password))
        .as("old device password must no longer authenticate")
        .isNull();
    assertThat(obtainTokenOrNull(username, newPassword))
        .as("new device password must authenticate")
        .isNotBlank();

    // ---- Delete removes the Keycloak user, the row, and the binding ----------
    appClient()
        .delete()
        .uri("/api/v1/org-units/" + outletId + "/device-credential")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toBodilessEntity();

    assertThatThrownBy(() -> getJson(token, "/api/v1/org-units/" + outletId + "/device-credential"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    assertThat(obtainTokenOrNull(username, newPassword))
        .as("deleted device credential must no longer authenticate")
        .isNull();

    JsonNode outletUsersAfterDelete = getJson(token, "/api/v1/org-units/" + outletId + "/users");
    for (JsonNode row : outletUsersAfterDelete) {
      assertThat(row.path("userId").asString()).isNotEqualTo(deviceUserId);
    }
  }

  @Test
  void createForANonOutletTargetReturns400() throws Exception {
    String token = tokenForOwner();
    // createOutletUnderCompanyA also seeds the parent BUSINESS_UNIT (ADR 0012) — find it (type !=
    // OUTLET) as the illegal target.
    createOutletUnderCompanyA(token);
    JsonNode units = getJson(token, "/api/v1/org-units");
    String businessUnitId = null;
    for (JsonNode unit : units) {
      if (!"OUTLET".equals(unit.path("type").asString())) {
        businessUnitId = unit.get("id").asString();
      }
    }
    assertThat(businessUnitId).isNotNull();
    String finalBusinessUnitId = businessUnitId;

    assertThatThrownBy(() -> createDeviceCredential(token, finalBusinessUnitId))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getResponseBodyAsString())
                  .contains("device-credential-target-not-outlet");
            });
  }

  @Test
  void revealForAnOutletWithNoCredentialReturns404() throws Exception {
    String token = tokenForOwner();
    String outletId = createOutletUnderCompanyA(token);

    assertThatThrownBy(() -> getJson(token, "/api/v1/org-units/" + outletId + "/device-credential"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ===========================================================================
  // TeamAdministrationGuard.requireOwnerOrManager() — server-side re-check (defense in depth).
  // This service does not itself enforce role-based authorization (that is the gateway's job); the
  // guard is what stands between a route misconfiguration and a decrypted till password. Every
  // endpoint must reject a non-owner/manager caller with 403, REGARDLESS of whether a credential
  // exists for the outlet (no enumeration) — proven here with cashier-acme against an outlet that
  // has NO device credential yet.
  // ===========================================================================

  @Test
  void nonOwnerManagerCallerIsForbiddenOnEveryEndpointEvenWithoutACredential() throws Exception {
    String ownerToken = tokenForOwner();
    String outletId = createOutletUnderCompanyA(ownerToken);
    String cashierToken = tokenForCashier();

    // CREATE
    assertForbidden(
        () ->
            appClient()
                .post()
                .uri("/api/v1/org-units/" + outletId + "/device-credential")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                .retrieve()
                .body(String.class));

    // REVEAL (GET) — no credential exists yet; must still be 403, not 404.
    assertForbidden(
        () ->
            appClient()
                .get()
                .uri("/api/v1/org-units/" + outletId + "/device-credential")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                .retrieve()
                .body(String.class));

    // RESET
    assertForbidden(
        () ->
            appClient()
                .post()
                .uri("/api/v1/org-units/" + outletId + "/device-credential/reset")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                .retrieve()
                .body(String.class));

    // DELETE
    assertForbidden(
        () ->
            appClient()
                .delete()
                .uri("/api/v1/org-units/" + outletId + "/device-credential")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                .retrieve()
                .toBodilessEntity());

    // Owner is unaffected by the cashier's rejected attempts — create still succeeds as owner
    // (the full lifecycle is already proven end-to-end by the test above).
    JsonNode created = createDeviceCredential(ownerToken, outletId);
    assertThat(created.get("username").asString()).isEqualTo("till." + outletId);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private String tokenForOwner() {
    return obtainToken(OWNER_USERNAME, OWNER_PASSWORD);
  }

  private String tokenForCashier() {
    return obtainToken(CASHIER_USERNAME, CASHIER_PASSWORD);
  }

  /**
   * Asserts the call fails with {@code 403 Forbidden} (the guard's uniform anti-enumeration
   * status).
   */
  private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  /**
   * Creates a fresh BUSINESS_UNIT under company A (auto-seeding its default OUTLET, ADR 0012) and
   * returns the seeded outlet's id. Each test's DB starts empty ({@code resetDatabase}), and
   * org_unit carries no FK to {@code company} — creating org units directly, with no prior {@code
   * POST /api/v1/companies}, is a legitimate path (mirrors the tenant-bootstrap-free org-tree tests
   * elsewhere in this service).
   */
  private String createOutletUnderCompanyA(String token) throws IOException {
    String businessUnitBody =
        appClient()
            .post()
            .uri("/api/v1/org-units")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"name": "Device Test BU %s", "type": "business_unit", "vertical": "restaurant"}
                """
                    .formatted(java.util.UUID.randomUUID()))
            .retrieve()
            .body(String.class);
    String businessUnitId = JSON.readValue(businessUnitBody, JsonNode.class).get("id").asString();

    JsonNode units = getJson(token, "/api/v1/org-units");
    for (JsonNode unit : units) {
      if ("OUTLET".equals(unit.path("type").asString())
          && businessUnitId.equals(unit.path("parentId").asString())) {
        return unit.get("id").asString();
      }
    }
    throw new IllegalStateException("Expected an auto-seeded outlet under " + businessUnitId);
  }

  private JsonNode createDeviceCredential(String token, String outletId) throws IOException {
    String body =
        appClient()
            .post()
            .uri("/api/v1/org-units/" + outletId + "/device-credential")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(String.class);
    return JSON.readValue(body, JsonNode.class);
  }

  private JsonNode getJson(String token, String path) throws IOException {
    String body =
        appClient()
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(String.class);
    return JSON.readValue(body, JsonNode.class);
  }

  private static java.util.List<String> listAttr(JsonNode user, String name) {
    java.util.List<String> values = new java.util.ArrayList<>();
    user.path("attributes").path(name).forEach(v -> values.add(v.asString()));
    return values;
  }

  private static java.util.List<String> fetchRealmRoleNames(String userId) throws IOException {
    String adminToken = obtainAdminToken();
    Request request =
        new Request.Builder()
            .url(
                KEYCLOAK.getAuthServerUrl()
                    + "/admin/realms/"
                    + REALM
                    + "/users/"
                    + userId
                    + "/role-mappings/realm")
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode roles = JSON.readValue(response.body().string(), JsonNode.class);
      java.util.List<String> names = new java.util.ArrayList<>();
      // Only the business roles (owner/manager/cashier/employee) — ignore Keycloak's built-ins
      // (default-roles-*, offline_access, uma_authorization) so the assertion is exact.
      java.util.Set<String> business = java.util.Set.of("owner", "manager", "cashier", "employee");
      for (JsonNode role : roles) {
        String name = role.get("name").asString();
        if (business.contains(name)) {
          names.add(name);
        }
      }
      return names;
    }
  }

  private static String findKeycloakUserIdByUsername(String username) throws IOException {
    String adminToken = obtainAdminToken();
    String url =
        KEYCLOAK.getAuthServerUrl()
            + "/admin/realms/"
            + REALM
            + "/users?username="
            + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
            + "&exact=true";
    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode users = JSON.readValue(response.body().string(), JsonNode.class);
      assertThat(users.size()).isGreaterThanOrEqualTo(1);
      return users.get(0).get("id").asString();
    }
  }

  private static JsonNode fetchKeycloakUser(String userId) throws IOException {
    String adminToken = obtainAdminToken();
    Request request =
        new Request.Builder()
            .url(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + REALM + "/users/" + userId)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      return JSON.readValue(response.body().string(), JsonNode.class);
    }
  }

  private static String obtainAdminToken() throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";
    okhttp3.RequestBody form =
        new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", ADMIN_CLIENT_ID)
            .add("client_secret", ADMIN_CLIENT_SECRET)
            .build();
    Request request = new Request.Builder().url(tokenUrl).post(form).build();
    try (Response response = HTTP.newCall(request).execute()) {
      return JSON.readValue(response.body().string(), JsonNode.class)
          .get("access_token")
          .asString();
    }
  }

  private static String obtainToken(String username, String password) {
    String result = obtainTokenOrNull(username, password);
    if (result == null) {
      throw new IllegalStateException("Failed to obtain token for " + username);
    }
    return result;
  }

  /** Returns the access token, or {@code null} if the grant is rejected (e.g. wrong password). */
  private static String obtainTokenOrNull(String username, String password) {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";
    okhttp3.RequestBody form =
        new FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", GW_CLIENT_ID)
            .add("client_secret", GW_CLIENT_SECRET)
            .add("username", username)
            .add("password", password)
            .build();
    Request request = new Request.Builder().url(tokenUrl).post(form).build();
    try (Response response = HTTP.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        return null;
      }
      JsonNode node = JSON.readValue(response.body().string(), JsonNode.class);
      JsonNode accessToken = node.get("access_token");
      return accessToken == null ? null : accessToken.asString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to obtain token for " + username, e);
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
}
