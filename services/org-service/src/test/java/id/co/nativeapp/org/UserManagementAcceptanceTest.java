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
import okhttp3.RequestBody;
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
 * End-to-end acceptance test for the user management endpoints ({@code /api/v1/users}).
 *
 * <p>Runs under the {@code secured} profile (real Keycloak, real Postgres) to exercise the full
 * security chain: JWT → TenantBindingFilter → TenantContext → UserService → KeycloakAdminClient.
 *
 * <p>Assertions:
 *
 * <ul>
 *   <li><strong>Invite</strong>: POST /api/v1/users creates a KC user with the caller's company_id
 *       attribute, the chosen role, UPDATE_PASSWORD required action, and returns a non-null
 *       one-time temporary password.
 *   <li><strong>List</strong>: GET /api/v1/users returns only users of the caller's company.
 *   <li><strong>Cross-tenant guard</strong>: PATCH/DELETE on another company's user id → 404.
 *   <li><strong>Role validation</strong>: POST/PATCH with an invalid role → 400.
 *   <li><strong>Self-lockout</strong>: owner cannot deactivate or demote themselves → 409.
 *   <li><strong>Patch</strong>: PATCH /api/v1/users/{id} updates role and/or enabled status.
 * </ul>
 *
 * <p>The test realm ({@code native-realm.json}) pre-seeds:
 *
 * <ul>
 *   <li>{@code owner-acme} — company {@code 11111111...} (COMPANY_A), roles: owner, manager
 *   <li>{@code owner-beta} — company {@code 22222222...} (COMPANY_B), roles: owner
 * </ul>
 *
 * <p>Both are Keycloak users NOT created by our API — used as the authenticated callers via the
 * {@code native-gateway} client (direct-access grant). The test creates NEW users via the API and
 * verifies their attributes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured")
@Testcontainers
class UserManagementAcceptanceTest {

  // ---- Keycloak realm constants -------------------------------------------------
  private static final String REALM = "native";
  private static final String ADMIN_CLIENT_ID = "native-admin";
  private static final String ADMIN_CLIENT_SECRET = "native-admin-secret";
  // Gateway client — direct-access grants enabled, maps company_id + roles
  private static final String GW_CLIENT_ID = "native-gateway";
  private static final String GW_CLIENT_SECRET = "native-gateway-secret";
  // Seeded users (pre-exist in the realm JSON — not created by the API)
  private static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";
  private static final String OWNER_A_USERNAME = "owner-acme";
  private static final String OWNER_A_PASSWORD = "owner-password";
  private static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";
  private static final String OWNER_B_USERNAME = "owner-beta";
  private static final String OWNER_B_PASSWORD = "beta-owner-password";

  private static final String APP_USER = "app_user";
  private static final String APP_PASSWORD = "app_secret";

  // ---- Test containers ---------------------------------------------------------
  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFiles("/native-realm.json");

  @SuppressWarnings("resource")
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private static final OkHttpClient HTTP = new OkHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  // ---- Keycloak user ids for the seeded users, populated after startup ----------
  // We need the KC user id of owner-acme to test self-lockout.
  private static String ownerAKeycloakId;
  private static String ownerBKeycloakId;

  @LocalServerPort private int port;

  // ---- Setup -------------------------------------------------------------------

  @BeforeAll
  static synchronized void startContainers() {
    KEYCLOAK.start();
    POSTGRES.start();
    provisionAppRole();
    // Resolve the Keycloak user ids of the seeded users (needed for self-lockout assertions).
    ownerAKeycloakId = findKeycloakUserIdByEmail("owner@acme.example.co.id");
    ownerBKeycloakId = findKeycloakUserIdByEmail("owner@beta.example.co.id");
  }

  @BeforeEach
  void resetDatabase() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE company, org_unit, legal_employer, outbox");
    } catch (SQLException ignored) {
      // Not yet migrated (first run) — nothing to reset.
    }
    // NOTE: we do NOT reset Keycloak users created by the test API calls here.
    // Tests use unique email addresses per run (uniqueEmail()) to avoid cross-test collisions.
    // The seeded realm users (owner-acme, owner-beta, cashier-acme) are NOT deleted.
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
  // Invite tests
  // ===========================================================================

  @Test
  void inviteCreatesKeycloakUserWithCompanyIdAndRoleAndReturnsTemporaryPassword() throws Exception {
    String email = uniqueEmail();
    String tokenA = tokenForOwnerA();

    String body =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"email": "%s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);

    JsonNode node = JSON.readValue(body, JsonNode.class);
    assertThat(node.get("id").asString()).isNotBlank();
    assertThat(node.get("email").asString()).isEqualTo(email);
    assertThat(node.get("role").asString()).isEqualTo("cashier");
    String tempPassword = node.get("temporaryPassword").asString();
    assertThat(tempPassword).isNotBlank();

    // Verify in Keycloak: user exists, has the right company_id and UPDATE_PASSWORD action.
    String userId = node.get("id").asString();
    verifyKeycloakUserInvited(userId, email, COMPANY_A, "cashier");
  }

  @Test
  void inviteWithDuplicateEmailReturns409() throws Exception {
    String email = uniqueEmail();
    String tokenA = tokenForOwnerA();

    // First invite succeeds.
    appClient()
        .post()
        .uri("/api/v1/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"email": "%s", "role": "manager"}
            """
                .formatted(email))
        .retrieve()
        .body(String.class);

    // Second invite with the same email → 409.
    assertThatThrownBy(
            () ->
                appClient()
                    .post()
                    .uri("/api/v1/users")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {"email": "%s", "role": "cashier"}
                        """
                            .formatted(email))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  // ===========================================================================
  // List tests
  // ===========================================================================

  @Test
  void listReturnsOnlyCallerCompanyUsers() throws Exception {
    String emailA = uniqueEmail();
    String tokenA = tokenForOwnerA();

    // Invite a user into company A.
    appClient()
        .post()
        .uri("/api/v1/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"email": "%s", "role": "cashier"}
            """
                .formatted(emailA))
        .retrieve()
        .body(String.class);

    // List as owner-A: should include the newly invited user and owner-acme itself,
    // but NOT include owner-beta (company B).
    String listBody =
        appClient()
            .get()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .retrieve()
            .body(String.class);

    JsonNode users = JSON.readValue(listBody, JsonNode.class);
    assertThat(users.isArray()).isTrue();

    // All returned users must have the caller's company id (verified via the invite we just did).
    // The seeded owner-acme also belongs to company A, so it should also appear.
    // owner-beta (company B) must NOT appear.
    boolean foundInvited = false;
    for (JsonNode user : users) {
      // Only email check here — no PII assertion on other users we don't own.
      if (emailA.equalsIgnoreCase(user.get("email").asString())) {
        foundInvited = true;
        assertThat(user.get("roles").toString()).contains("cashier");
      }
      // owner-beta must not appear
      assertThat(user.get("email").asString()).doesNotContain("beta");
    }
    assertThat(foundInvited).isTrue();
  }

  // ===========================================================================
  // Cross-tenant guard tests
  // ===========================================================================

  @Test
  void patchOnAnotherTenantsUserReturns404() throws Exception {
    // owner-B's KC id is a user that belongs to company B.
    // As owner-A, patching that user must return 404 (not 403).
    String tokenA = tokenForOwnerA();

    assertThatThrownBy(
            () ->
                appClient()
                    .patch()
                    .uri("/api/v1/users/" + ownerBKeycloakId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"role\": \"manager\"}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              // RFC-7807 type present
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/user-not-found");
            });
  }

  @Test
  void deleteOnAnotherTenantsUserReturns404() throws Exception {
    // As owner-A, deactivating owner-B's user must return 404.
    String tokenA = tokenForOwnerA();

    assertThatThrownBy(
            () ->
                appClient()
                    .delete()
                    .uri("/api/v1/users/" + ownerBKeycloakId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void listAsOwnerBDoesNotIncludeCompanyAUsers() throws Exception {
    // owner-B should only see company-B users, not company-A users.
    String tokenB = tokenForOwnerB();

    String listBody =
        appClient()
            .get()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
            .retrieve()
            .body(String.class);

    JsonNode users = JSON.readValue(listBody, JsonNode.class);
    assertThat(users.isArray()).isTrue();

    for (JsonNode user : users) {
      assertThat(user.get("email").asString()).doesNotContain("acme");
    }
  }

  // ===========================================================================
  // Role validation tests
  // ===========================================================================

  @Test
  void inviteWithInvalidRoleReturns400() {
    String tokenA = tokenForOwnerA();

    assertThatThrownBy(
            () ->
                appClient()
                    .post()
                    .uri("/api/v1/users")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {"email": "%s", "role": "superadmin"}
                        """
                            .formatted(uniqueEmail()))
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/invalid-role");
            });
  }

  @Test
  void patchWithInvalidRoleReturns400() throws Exception {
    // Invite a user first.
    String email = uniqueEmail();
    String tokenA = tokenForOwnerA();
    String inviteBody =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"email": "%s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);
    String userId = JSON.readValue(inviteBody, JsonNode.class).get("id").asString();

    // Patch with an invalid role.
    assertThatThrownBy(
            () ->
                appClient()
                    .patch()
                    .uri("/api/v1/users/" + userId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"role\": \"cfo\"}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/invalid-role");
            });
  }

  // ===========================================================================
  // Self-lockout tests
  // ===========================================================================

  @Test
  void ownerCannotDeactivateThemselves() {
    // ownerAKeycloakId is the Keycloak id of the owner-acme user, which matches the JWT sub
    // that the gateway injects as X-Actor when owner-acme authenticates.
    // The UserService compares target.id() == caller's actor.
    String tokenA = tokenForOwnerA();

    assertThatThrownBy(
            () ->
                appClient()
                    .delete()
                    .uri("/api/v1/users/" + ownerAKeycloakId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/self-lockout");
            });
  }

  @Test
  void ownerCannotDisableThemselvesViaPatch() {
    String tokenA = tokenForOwnerA();

    assertThatThrownBy(
            () ->
                appClient()
                    .patch()
                    .uri("/api/v1/users/" + ownerAKeycloakId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"enabled\": false}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/self-lockout");
            });
  }

  @Test
  void ownerCannotDemoteThemselvesByPatch() {
    String tokenA = tokenForOwnerA();

    // owner-acme has the "owner" role; trying to change it to "manager" on self is a self-demotion.
    assertThatThrownBy(
            () ->
                appClient()
                    .patch()
                    .uri("/api/v1/users/" + ownerAKeycloakId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"role\": \"manager\"}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/self-lockout");
            });
  }

  // ===========================================================================
  // Patch happy-path
  // ===========================================================================

  @Test
  void patchCanUpdateRoleOfAnInvitedUser() throws Exception {
    String email = uniqueEmail();
    String tokenA = tokenForOwnerA();

    String inviteBody =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"email": "%s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);
    String userId = JSON.readValue(inviteBody, JsonNode.class).get("id").asString();

    // Patch: promote to manager.
    String patchBody =
        appClient()
            .patch()
            .uri("/api/v1/users/" + userId)
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"role\": \"manager\"}")
            .retrieve()
            .body(String.class);

    JsonNode updated = JSON.readValue(patchBody, JsonNode.class);
    assertThat(updated.get("roles").toString()).contains("manager");
    assertThat(updated.get("id").asString()).isEqualTo(userId);
  }

  @Test
  void patchBothNullFieldsReturns400() throws Exception {
    String email = uniqueEmail();
    String tokenA = tokenForOwnerA();

    String inviteBody =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"email": "%s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);
    String userId = JSON.readValue(inviteBody, JsonNode.class).get("id").asString();

    // PATCH with no fields → 400.
    assertThatThrownBy(
            () ->
                appClient()
                    .patch()
                    .uri("/api/v1/users/" + userId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(String.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private String tokenForOwnerA() {
    return obtainToken(OWNER_A_USERNAME, OWNER_A_PASSWORD);
  }

  private String tokenForOwnerB() {
    return obtainToken(OWNER_B_USERNAME, OWNER_B_PASSWORD);
  }

  private String obtainToken(String username, String password) {
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
      JsonNode node = JSON.readValue(response.body().string(), JsonNode.class);
      return node.get("access_token").asString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to obtain token for " + username, e);
    }
  }

  /**
   * Looks up a Keycloak user id by exact email match. Used in {@link #startContainers()} to resolve
   * the seeded users' KC ids before tests run.
   */
  private static String findKeycloakUserIdByEmail(String email) {
    String adminToken = obtainAdminToken();
    String url =
        KEYCLOAK.getAuthServerUrl()
            + "/admin/realms/"
            + REALM
            + "/users?email="
            + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
            + "&exact=true";
    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode users = JSON.readValue(response.body().string(), JsonNode.class);
      assertThat(users.isArray()).isTrue();
      assertThat(users.size()).isGreaterThanOrEqualTo(1);
      return users.get(0).get("id").asString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to find KC user for email: " + email, e);
    }
  }

  /**
   * Verifies via the Keycloak Admin API that the invited user has the expected company_id
   * attribute, the specified role, and the UPDATE_PASSWORD required action.
   */
  private void verifyKeycloakUserInvited(
      String userId, String email, String expectedCompanyId, String expectedRole)
      throws IOException {
    String adminToken = obtainAdminToken();

    // Fetch the user by id.
    String userUrl = KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + REALM + "/users/" + userId;
    Request userRequest =
        new Request.Builder()
            .url(userUrl)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(userRequest).execute()) {
      assertThat(response.code()).isEqualTo(200);
      JsonNode user = JSON.readValue(response.body().string(), JsonNode.class);

      // company_id attribute must match.
      JsonNode companyIds = user.path("attributes").path("company_id");
      assertThat(companyIds.isArray()).isTrue();
      assertThat(companyIds.get(0).asString()).isEqualTo(expectedCompanyId);

      // UPDATE_PASSWORD must be a required action.
      JsonNode requiredActions = user.path("requiredActions");
      assertThat(requiredActions.isArray()).isTrue();
      boolean hasUpdatePassword = false;
      for (JsonNode action : requiredActions) {
        if ("UPDATE_PASSWORD".equals(action.asString())) {
          hasUpdatePassword = true;
        }
      }
      assertThat(hasUpdatePassword)
          .as("Expected UPDATE_PASSWORD required action for invited user")
          .isTrue();
    }

    // Verify role assignment via realm role-mappings.
    String roleMappingsUrl =
        KEYCLOAK.getAuthServerUrl()
            + "/admin/realms/"
            + REALM
            + "/users/"
            + userId
            + "/role-mappings/realm";
    Request rolesRequest =
        new Request.Builder()
            .url(roleMappingsUrl)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(rolesRequest).execute()) {
      assertThat(response.code()).isEqualTo(200);
      JsonNode roles = JSON.readValue(response.body().string(), JsonNode.class);
      boolean foundRole = false;
      for (JsonNode role : roles) {
        if (expectedRole.equals(role.get("name").asString())) {
          foundRole = true;
        }
      }
      assertThat(foundRole).as("Expected role '{}' to be assigned", expectedRole).isTrue();
    }
  }

  private static String obtainAdminToken() {
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
    } catch (IOException e) {
      throw new RuntimeException("Failed to obtain admin token", e);
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

  private static String uniqueEmail() {
    return "user-mgmt-test-" + System.nanoTime() + "@example.co.id";
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
