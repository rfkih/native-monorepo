package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
  // A manager-ONLY login in company A (owner-acme holds owner+manager, so it can't stand in for a
  // plain manager) — the caller for the role-hierarchy / anti-escalation tests.
  private static final String MANAGER_A_USERNAME = "manager-acme";
  private static final String MANAGER_A_PASSWORD = "manager-password";
  // A cashier-ONLY login in company A — the floor-login TARGET for the page-grant / outlet-
  // assignment role-hierarchy guard tests (a manager may administer this login).
  private static final String CASHIER_A_USERNAME = "cashier-acme";
  private static final String CASHIER_A_PASSWORD = "cashier-password";
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
  private static String managerAKeycloakId;
  private static String cashierAKeycloakId;

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
    managerAKeycloakId = findKeycloakUserIdByEmail("manager@acme.example.co.id");
    cashierAKeycloakId = findKeycloakUserIdByEmail("cashier@acme.example.co.id");
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
    // The invite flow now reads the caller company's company_code (ADR 0054) to compose
    // <company_code>.<local> usernames, so both tenants need a company row. adminConnection() is
    // the
    // container superuser (bypasses RLS), so it inserts across tenants directly; codes are FIXED so
    // the scoped-username assertions are deterministic. org_unit is NOT seeded — the code read is
    // JOIN-free.
    seedCompany(COMPANY_A, "acme01", "Acme");
    seedCompany(COMPANY_B, "beta01", "Beta");
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
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
  void inviteWithAUsernameAndNoEmailSucceeds() throws Exception {
    // Some employees have no email address — the login is keyed by username, email is optional.
    String username = "no.email." + java.util.UUID.randomUUID().toString().substring(0, 8);
    String tokenA = tokenForOwnerA();

    String body =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"username": "%s", "role": "employee"}
                """
                    .formatted(username))
            .retrieve()
            .body(String.class);

    JsonNode node = JSON.readValue(body, JsonNode.class);
    assertThat(node.get("id").asString()).isNotBlank();
    assertThat(node.get("username").asString()).isEqualTo(username);
    // No email provided → the response carries no email (absent or explicit null).
    assertThat(node.hasNonNull("email")).isFalse();
    assertThat(node.get("role").asString()).isEqualTo("employee");
    assertThat(node.get("temporaryPassword").asString()).isNotBlank();
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
            {"username": "%1$s", "email": "%1$s", "role": "manager"}
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
                        {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
  // Company-scoped usernames (ADR 0054)
  // ===========================================================================

  @Test
  void twoTenantsCanEachInviteTheSameLocalNameAndTheStoredNameIsCompanyScoped() throws Exception {
    // The stored Keycloak username is <company_code>.<local>, so the SAME short local name is
    // unique PER COMPANY — the 409 is now per-company, not global. Both invites succeed; the
    // response surfaces the LOCAL name, while the stored KC usernames carry each company's prefix.
    String local = "budi" + System.nanoTime();

    JsonNode nodeA =
        JSON.readValue(inviteLocal(tokenForOwnerA(), local, "cashier"), JsonNode.class);
    JsonNode nodeB =
        JSON.readValue(inviteLocal(tokenForOwnerB(), local, "cashier"), JsonNode.class);

    assertThat(nodeA.get("username").asString()).isEqualTo(local);
    assertThat(nodeB.get("username").asString()).isEqualTo(local);

    assertThat(keycloakUsername(nodeA.get("id").asString())).isEqualTo("acme01." + local);
    assertThat(keycloakUsername(nodeB.get("id").asString())).isEqualTo("beta01." + local);
  }

  @Test
  void sameTenantDuplicateLocalNameReturns409() throws Exception {
    String local = "kasir" + System.nanoTime();
    String tokenA = tokenForOwnerA();

    // First invite of the local name under company A succeeds.
    inviteLocal(tokenA, local, "cashier");

    // Second invite of the SAME local name under the SAME company → 409 (composed name collides).
    assertThatThrownBy(() -> inviteLocal(tokenA, local, "employee"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex ->
                assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void listShowsTheLocalUsernameNotTheStoredCompanyScopedName() throws Exception {
    // GET /users strips the <companyCode>. prefix for display, so the console shows what the owner
    // typed — never the raw scoped Keycloak username.
    String local = "sari" + System.nanoTime();
    String tokenA = tokenForOwnerA();
    inviteLocal(tokenA, local, "cashier");

    String listBody =
        appClient()
            .get()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .retrieve()
            .body(String.class);
    JsonNode users = JSON.readValue(listBody, JsonNode.class);

    boolean found = false;
    for (JsonNode user : users) {
      // No listed login shows a raw company-code prefix.
      assertThat(user.get("username").asString()).doesNotStartWith("acme01.");
      if (local.equals(user.get("username").asString())) {
        found = true;
      }
    }
    assertThat(found).as("the scoped login should list by its local username").isTrue();
  }

  /** Invites a login by a bare local username (no email) and returns the raw response body. */
  private String inviteLocal(String token, String localUsername, String role) {
    return appClient()
        .post()
        .uri("/api/v1/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"username\": \"%s\", \"role\": \"%s\"}".formatted(localUsername, role))
        .retrieve()
        .body(String.class);
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
            {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
                    .body("{\"roles\": [\"manager\"]}")
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
  void inviteWithTheEmployeeRoleSucceeds() throws Exception {
    // The employee self-service surface hinges on this role being invitable; it must pass the
    // ALLOWED_ROLES whitelist and be assignable as a realm role end-to-end.
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
                {"username": "%1$s", "email": "%1$s", "role": "employee"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);

    JsonNode invited = JSON.readValue(body, JsonNode.class);
    assertThat(invited.get("role").asString()).isEqualTo("employee");
    assertThat(invited.get("temporaryPassword").asString()).isNotBlank();
  }

  // ===========================================================================
  // Preset role-based access model Phase 1 — the 4 new roles (hr/accountant/chef/waitress)
  // ===========================================================================

  @Test
  void inviteWithEachOfTheFourNewRolesSucceeds() throws Exception {
    // hr/accountant/chef/waitress must all pass the extended ALLOWED_ROLES whitelist and be
    // assignable as real Keycloak realm roles end-to-end — the same proof
    // inviteWithTheEmployeeRoleSucceeds gives the original four.
    for (String role : List.of("hr", "accountant", "chef", "waitress")) {
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
                  {"username": "%1$s", "email": "%1$s", "role": "%2$s"}
                  """
                      .formatted(email, role))
              .retrieve()
              .body(String.class);

      JsonNode invited = JSON.readValue(body, JsonNode.class);
      assertThat(invited.get("role").asString()).as("role for %s", role).isEqualTo(role);
      assertThat(invited.get("temporaryPassword").asString()).isNotBlank();
    }
  }

  @Test
  void inviteWithAdditionalRolesPersistsTheFullRoleSet() throws Exception {
    // A single login can hold several roles at once (e.g. hr + accountant) — additionalRoles is
    // the invite-time mechanism; access at the gateway is the union of every held role's surfaces.
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
                {"username": "%1$s", "email": "%1$s", "role": "hr", "additionalRoles": ["accountant"]}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);

    JsonNode invited = JSON.readValue(body, JsonNode.class);
    assertThat(invited.get("role").asString()).isEqualTo("hr");
    assertThat(invited.get("roles").toString()).contains("hr").contains("accountant");

    String userId = invited.get("id").asString();
    verifyKeycloakUserInvited(userId, email, COMPANY_A, "hr");
    verifyKeycloakUserInvited(userId, email, COMPANY_A, "accountant");
  }

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
                        {"username": "%1$s", "email": "%1$s", "role": "superadmin"}
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
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
                    .body("{\"roles\": [\"cfo\"]}")
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
                    .body("{\"roles\": [\"manager\"]}")
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
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
            .body("{\"roles\": [\"manager\"]}")
            .retrieve()
            .body(String.class);

    JsonNode updated = JSON.readValue(patchBody, JsonNode.class);
    assertThat(updated.get("roles").toString()).contains("manager");
    assertThat(updated.get("id").asString()).isEqualTo(userId);
  }

  @Test
  void patchWithARoleSetReplacesTheEntireRoleSetAndPersistsAllRoles() throws Exception {
    // Preset role-based access model Phase 1: PATCH roles is a SET, not a single role — the
    // response (and Keycloak's own role-mapping list) must carry every element, and the replace
    // must be a full replacement (the invited "cashier" role must be GONE after the patch).
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
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);
    String userId = JSON.readValue(inviteBody, JsonNode.class).get("id").asString();

    // Patch: replace the single "cashier" role with the SET {hr, accountant}.
    String patchBody =
        appClient()
            .patch()
            .uri("/api/v1/users/" + userId)
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"roles\": [\"hr\", \"accountant\"]}")
            .retrieve()
            .body(String.class);

    JsonNode updated = JSON.readValue(patchBody, JsonNode.class);
    assertThat(updated.get("roles").toString()).contains("hr").contains("accountant");
    assertThat(updated.get("roles").toString()).doesNotContain("cashier");
    assertThat(updated.get("id").asString()).isEqualTo(userId);

    // Re-list to confirm the change is durable (not just echoed back from the request).
    String listBody =
        appClient()
            .get()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
            .retrieve()
            .body(String.class);
    JsonNode users = JSON.readValue(listBody, JsonNode.class);
    boolean found = false;
    for (JsonNode user : users) {
      if (userId.equals(user.get("id").asString())) {
        found = true;
        assertThat(user.get("roles").toString()).contains("hr").contains("accountant");
        assertThat(user.get("roles").toString()).doesNotContain("cashier");
      }
    }
    assertThat(found).as("patched user should still appear in the company's user list").isTrue();
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
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
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
  // Role-hierarchy / anti-escalation (ADR 0052) — the gateway lets owner AND manager reach
  // /users/**; these prove the fine-grained guard that stops a MANAGER from escalating.
  // ===========================================================================

  @Test
  void managerCannotEscalateSelfToOwnerByPatch() {
    // The takeover attack: a manager PATCHes their own login to {owner}. Must be 403, not a
    // demotion
    // (self-lockout doesn't fire — the manager holds no owner role to lose).
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .patch()
                .uri("/api/v1/users/" + managerAKeycloakId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"roles\": [\"owner\"]}")
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCannotSelfGrantAccountantOrHrByPatch() {
    // The boundary-defeat attack: a manager PATCHes self to {manager, accountant, hr},
    // self-granting
    // the finance + payroll surfaces ADR 0052 withholds from managers. Must be 403.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .patch()
                .uri("/api/v1/users/" + managerAKeycloakId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"roles\": [\"manager\", \"accountant\", \"hr\"]}")
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCannotInviteAnOwner() {
    // The escalation-by-invite variant: a manager creates a new owner and gets its temp password.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .post()
                .uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {"username": "%1$s", "email": "%1$s", "role": "owner"}
                    """
                        .formatted(uniqueEmail()))
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCannotInviteAnAccountant() {
    // A manager may not hand out finance access (accountant) it does not itself hold.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .post()
                .uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {"username": "%1$s", "email": "%1$s", "role": "accountant"}
                    """
                        .formatted(uniqueEmail()))
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCannotDemoteAnOwnerByPatch() {
    // The "strip the real owner" attack: a manager PATCHes owner-acme (a privileged login) down to
    // cashier. The target-hierarchy guard forbids a non-owner from touching an owner login → 403.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .patch()
                .uri("/api/v1/users/" + ownerAKeycloakId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"roles\": [\"cashier\"]}")
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCannotDeactivateAnOwner() {
    // A manager may not disable an owner login (lockout / DoS). Deactivate carries the same guard.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .delete()
                .uri("/api/v1/users/" + ownerAKeycloakId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .retrieve()
                .toBodilessEntity());
  }

  @Test
  void managerCanInviteAFloorLogin() throws Exception {
    // The guard must NOT over-block: a manager runs the floor, so inviting a cashier still
    // succeeds.
    String email = uniqueEmail();
    String tokenM = tokenForManagerA();

    String body =
        appClient()
            .post()
            .uri("/api/v1/users")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"username": "%1$s", "email": "%1$s", "role": "cashier"}
                """
                    .formatted(email))
            .retrieve()
            .body(String.class);

    JsonNode node = JSON.readValue(body, JsonNode.class);
    assertThat(node.get("role").asString()).isEqualTo("cashier");
    assertThat(node.get("temporaryPassword").asString()).isNotBlank();
  }

  // ===========================================================================
  // Role-hierarchy guard on the page-grant / outlet-assignment replace-set PUTs (the flagged gap —
  // security-review Finding: TeamAdministrationGuard was extracted so these two sibling write
  // paths get the SAME fine-grained owner/manager boundary as invite/patch/deactivate.
  // ===========================================================================

  @Test
  void managerCannotReplacePagesForAnOwner() {
    // The target-hierarchy check: a manager may not touch a privileged (owner) login's page grants,
    // even though no role is being changed.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .put()
                .uri("/api/v1/users/" + ownerAKeycloakId + "/pages")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pageKeys\": [\"pos\"]}")
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCanReplacePagesForAFloorLogin() throws Exception {
    // The guard must NOT over-block: a manager may re-scope a cashier's (floor login) pages.
    String tokenM = tokenForManagerA();

    String body =
        appClient()
            .put()
            .uri("/api/v1/users/" + cashierAKeycloakId + "/pages")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"pageKeys\": [\"pos\"]}")
            .retrieve()
            .body(String.class);

    JsonNode node = JSON.readValue(body, JsonNode.class);
    assertThat(node.get("mode").asString()).isEqualTo("RESTRICTED");
    assertThat(node.get("pageKeys").toString()).contains("pos");
  }

  @Test
  void managerCannotReplaceOutletsForAnOwner() {
    // Same target-hierarchy check on the outlet-assignment replace-set PUT.
    String tokenM = tokenForManagerA();
    assertInsufficientPrivilege(
        () ->
            appClient()
                .put()
                .uri("/api/v1/users/" + ownerAKeycloakId + "/outlets")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"orgUnitIds\": []}")
                .retrieve()
                .body(String.class));
  }

  @Test
  void managerCanReplaceOutletsForAFloorLoginWithAnEmptySet() throws Exception {
    // The guard must NOT over-block: a manager may re-scope a cashier's outlet assignments. An
    // empty set ("remove all") needs no valid outlet id, isolating this test to the guard alone.
    String tokenM = tokenForManagerA();

    String body =
        appClient()
            .put()
            .uri("/api/v1/users/" + cashierAKeycloakId + "/outlets")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenM))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"orgUnitIds\": []}")
            .retrieve()
            .body(String.class);

    JsonNode node = JSON.readValue(body, JsonNode.class);
    assertThat(node.isArray()).isTrue();
    assertThat(node).isEmpty();
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Asserts the call fails with 403 + the {@code insufficient-privilege} RFC-7807 type. */
  private static void assertInsufficientPrivilege(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseBodyAsString())
                  .contains("https://errors.nativeapp.id/insufficient-privilege");
            });
  }

  private String tokenForOwnerA() {
    return obtainToken(OWNER_A_USERNAME, OWNER_A_PASSWORD);
  }

  private String tokenForOwnerB() {
    return obtainToken(OWNER_B_USERNAME, OWNER_B_PASSWORD);
  }

  private String tokenForManagerA() {
    return obtainToken(MANAGER_A_USERNAME, MANAGER_A_PASSWORD);
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

  /** Fetches the stored Keycloak {@code username} for a user id via the Admin API (ADR 0054). */
  private String keycloakUsername(String userId) throws IOException {
    String adminToken = obtainAdminToken();
    String userUrl = KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + REALM + "/users/" + userId;
    Request request =
        new Request.Builder()
            .url(userUrl)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      JsonNode user = JSON.readValue(response.body().string(), JsonNode.class);
      return user.get("username").asString();
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

  /**
   * Inserts a minimal {@code company} row for {@code companyId} with a FIXED {@code company_code}
   * (ADR 0054), via the container superuser connection (bypasses RLS, cross-tenant). Only the
   * mandatory NOT-NULL columns plus what the invite flow's JOIN-free code read needs; {@code
   * org_unit} is deliberately NOT seeded.
   */
  private static void seedCompany(String companyId, String companyCode, String name) {
    try (Connection admin = adminConnection();
        var ps =
            admin.prepareStatement(
                "INSERT INTO company (id, name, base_currency, default_language, legal_employer_id,"
                    + " country, plan_tier, company_code, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id) VALUES (?::uuid, ?, 'IDR', 'id', ?::uuid,"
                    + " 'ID', 'FULL', ?, now(), 'test', now(), 'test', 0, ?)")) {
      ps.setString(1, companyId);
      ps.setString(2, name);
      ps.setString(3, companyId);
      ps.setString(4, companyCode);
      ps.setString(5, companyId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to seed company " + companyId, e);
    }
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
