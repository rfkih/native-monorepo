package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * End-to-end acceptance for MULTI-COMPANY OWNERSHIP (ADR 0021), under the {@code secured} profile
 * (real Keycloak + real Postgres): the full loop of {@code POST /api/v1/companies} binding its
 * creator (membership-first), the enlarged claim arriving on a fresh token, {@code GET
 * /api/v1/companies/mine} listing the memberships, the per-request {@code X-Company-Id} selection
 * re-binding the service tenant, a foreign selection rejected {@code 403}, and the team list's
 * Keycloak attribute search matching a MULTI-VALUED {@code company_id} attribute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured")
@Testcontainers
class MultiCompanyMembershipAcceptanceTest {

  private static final String REALM = "native";
  private static final String GW_CLIENT_ID = "native-gateway";
  private static final String GW_CLIENT_SECRET = "native-gateway-secret";
  private static final String OWNER_A_USERNAME = "owner-acme";
  private static final String OWNER_A_PASSWORD = "owner-password";
  private static final String OWNER_B_USERNAME = "owner-beta";
  private static final String OWNER_B_PASSWORD = "beta-owner-password";
  private static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";

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

  private static String ownerAKeycloakId;

  @LocalServerPort private int port;

  @BeforeAll
  static synchronized void startContainers() {
    KEYCLOAK.start();
    POSTGRES.start();
    provisionAppRole();
    ownerAKeycloakId = findKeycloakUserIdByEmail("owner@acme.example.co.id");
  }

  @BeforeEach
  void resetDatabase() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE company, org_unit, legal_employer, user_outlet_assignment, outbox");
    } catch (SQLException ignored) {
      // Not yet migrated — nothing to reset.
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
    registry.add("native.keycloak-admin.client-id", () -> "native-admin");
    registry.add("native.keycloak-admin.client-secret", () -> "native-admin-secret");
  }

  @Test
  void createCompanyBindsTheCreatorAndTheSwitcherLoopWorksEndToEnd() throws Exception {
    // 1) owner-acme (claim = [COMPANY_A]) creates a SECOND business via the authenticated create.
    String tokenBefore = obtainToken(OWNER_A_USERNAME, OWNER_A_PASSWORD);
    String secondId = createCompany(tokenBefore, "Second Business");

    // 2) The creator's Keycloak membership gained the new company (membership-first bind).
    List<String> attribute = companyAttributeOf(ownerAKeycloakId);
    assertThat(attribute).contains(secondId);
    assertThat(attribute.size()).isGreaterThanOrEqualTo(2);

    // 3) A FRESH token carries the enlarged claim; /mine lists the memberships whose company rows
    //    exist (the original COMPANY_A row does not exist in this test DB → skipped, proving the
    //    dangling-membership skip too).
    String tokenAfter = obtainToken(OWNER_A_USERNAME, OWNER_A_PASSWORD);
    JsonNode mine = getJson(tokenAfter, "/api/v1/companies/mine", null);
    List<String> mineIds = idsOf(mine);
    assertThat(mineIds).containsExactly(secondId);

    // 4) A third business; /mine (fresh token) lists both, claim order.
    String thirdId = createCompany(tokenAfter, "Third Business");
    String tokenThree = obtainToken(OWNER_A_USERNAME, OWNER_A_PASSWORD);
    JsonNode mine2 = getJson(tokenThree, "/api/v1/companies/mine", null);
    assertThat(idsOf(mine2)).containsExactly(secondId, thirdId);

    // 5) The per-request X-Company-Id selection re-binds the service tenant: /companies/current
    //    returns the SELECTED company's profile.
    JsonNode currentSecond = getJson(tokenThree, "/api/v1/companies/current", secondId);
    assertThat(currentSecond.get("name").asString()).isEqualTo("Second Business");
    JsonNode currentThird = getJson(tokenThree, "/api/v1/companies/current", thirdId);
    assertThat(currentThird.get("name").asString()).isEqualTo("Third Business");

    // 6) Selecting a company OUTSIDE the token's set (owner-beta's) → 403, never bound.
    assertThatThrownBy(() -> getJson(tokenThree, "/api/v1/companies/current", COMPANY_B))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            ex -> {
              HttpClientErrorException e = (HttpClientErrorException) ex;
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getResponseBodyAsString()).contains("invalid-company-selection");
            });

    // 7) The team list under the SELECTED company finds the multi-company owner — Keycloak's
    //    attribute search (q=company_id:{value}) matches a MULTI-VALUED attribute (the risk item).
    JsonNode team = getJson(tokenThree, "/api/v1/users", secondId);
    List<String> usernames = new ArrayList<>();
    team.forEach(
        u -> usernames.add(u.path("username").asString() + "|" + u.path("email").asString()));
    assertThat(usernames)
        .as("team of the second company (raw rows: %s)", team.toString())
        .anySatisfy(row -> assertThat(row).startsWith(OWNER_A_USERNAME));

    // 8) A login whose memberships have no company rows here (owner-beta) gets an empty /mine.
    String tokenBeta = obtainToken(OWNER_B_USERNAME, OWNER_B_PASSWORD);
    JsonNode mineBeta = getJson(tokenBeta, "/api/v1/companies/mine", null);
    assertThat(mineBeta.size()).isZero();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private String createCompany(String token, String name) throws IOException {
    String body =
        appClient()
            .post()
            .uri("/api/v1/companies")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"name": "%s", "baseCurrency": "IDR", "defaultLanguage": "id",
                 "firstBusiness": {"name": "Main", "vertical": "restaurant"}}
                """
                    .formatted(name))
            .retrieve()
            .body(String.class);
    return JSON.readValue(body, JsonNode.class).get("id").asString();
  }

  private JsonNode getJson(String token, String path, String selectedCompanyId) throws IOException {
    var spec = appClient().get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (selectedCompanyId != null) {
      spec = spec.header("X-Company-Id", selectedCompanyId);
    }
    return JSON.readValue(spec.retrieve().body(String.class), JsonNode.class);
  }

  private static List<String> idsOf(JsonNode companies) {
    List<String> ids = new ArrayList<>();
    companies.forEach(c -> ids.add(c.get("id").asString()));
    return ids;
  }

  /** The user's {@code company_id} attribute values, via the Keycloak Admin API. */
  private static List<String> companyAttributeOf(String keycloakUserId) throws IOException {
    String adminToken = obtainAdminToken();
    Request request =
        new Request.Builder()
            .url(
                KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + REALM + "/users/" + keycloakUserId)
            .header("Authorization", "Bearer " + adminToken)
            .get()
            .build();
    try (Response response = HTTP.newCall(request).execute()) {
      JsonNode user = JSON.readValue(response.body().string(), JsonNode.class);
      List<String> values = new ArrayList<>();
      JsonNode attr = user.path("attributes").path("company_id");
      attr.forEach(v -> values.add(v.asString()));
      return values;
    }
  }

  private static String obtainAdminToken() throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token";
    okhttp3.RequestBody form =
        new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", "native-admin")
            .add("client_secret", "native-admin-secret")
            .build();
    Request request = new Request.Builder().url(tokenUrl).post(form).build();
    try (Response response = HTTP.newCall(request).execute()) {
      return JSON.readValue(response.body().string(), JsonNode.class)
          .get("access_token")
          .asString();
    }
  }

  private static String obtainToken(String username, String password) {
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
      return JSON.readValue(response.body().string(), JsonNode.class)
          .get("access_token")
          .asString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to obtain token for " + username, e);
    }
  }

  private static String findKeycloakUserIdByEmail(String email) {
    try {
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
        return users.get(0).get("id").asString();
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to find KC user for email: " + email, e);
    }
  }

  private static void provisionAppRole() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
