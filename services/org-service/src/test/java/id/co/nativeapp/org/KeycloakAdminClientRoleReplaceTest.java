package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.user.config.KeycloakAdminProperties;
import id.co.nativeapp.org.user.service.KeycloakAdminClient;
import id.co.nativeapp.org.user.service.KeycloakAdminException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link KeycloakAdminClient#replaceRealmRoles} uses the ADD-before-REMOVE fail-safe
 * ordering (production incident fix): a diff is computed against the user's current business roles,
 * every newly-needed role is added FIRST, and only THEN are the no-longer-wanted roles removed. A
 * mid-operation failure during the add step can therefore never leave the user with fewer roles
 * than they started with — the old remove-all-then-add-all code could.
 *
 * <p>Runs against an in-JVM {@link MockWebServer} standing in for the Keycloak Admin REST API — no
 * Spring context, no Testcontainers/Docker. A {@link Dispatcher} routes by HTTP method + path so
 * the exact call order can be asserted via {@link MockWebServer#takeRequest()}.
 */
class KeycloakAdminClientRoleReplaceTest {

  private static final String USER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private MockWebServer server;
  private KeycloakAdminClient client;

  /** The JSON array body returned for the initial GET .../role-mappings/realm fetch. */
  private volatile String currentMappingsJson = "[]";

  /**
   * Role names whose GET .../roles/{name} lookup (the first step of assignRealmRole) returns 500.
   */
  private final Set<String> failRoleLookupFor = new HashSet<>();

  @BeforeEach
  void startFakeKeycloak() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            String method = request.getMethod();

            if ("POST".equals(method)
                && path != null
                && path.endsWith("/protocol/openid-connect/token")) {
              return json(200, "{\"access_token\":\"t\",\"expires_in\":300}");
            }
            if ("GET".equals(method) && path != null && path.endsWith("/role-mappings/realm")) {
              return json(200, currentMappingsJson);
            }
            if ("POST".equals(method) && path != null && path.endsWith("/role-mappings/realm")) {
              return new MockResponse().setResponseCode(204);
            }
            if ("DELETE".equals(method) && path != null && path.endsWith("/role-mappings/realm")) {
              return new MockResponse().setResponseCode(204);
            }
            if ("GET".equals(method) && path != null && path.contains("/roles/")) {
              String roleName = path.substring(path.lastIndexOf('/') + 1);
              if (failRoleLookupFor.contains(roleName)) {
                return new MockResponse().setResponseCode(500);
              }
              return json(200, "{\"id\":\"id-" + roleName + "\",\"name\":\"" + roleName + "\"}");
            }
            return new MockResponse().setResponseCode(404);
          }
        });
    server.start();

    KeycloakAdminProperties props = new KeycloakAdminProperties();
    props.setBaseUrl(URI.create("http://" + server.getHostName() + ":" + server.getPort()));
    props.setRealm("native");
    props.setClientId("native-admin");
    props.setClientSecret("test-secret-not-real");

    client = new KeycloakAdminClient(props);
  }

  @AfterEach
  void stopFakeKeycloak() throws Exception {
    server.shutdown();
  }

  @Test
  void addsTheMissingRoleAndDoesNotReAddAnAlreadyHeldRole() throws Exception {
    currentMappingsJson = "[{\"id\":\"c\",\"name\":\"cashier\"}]";

    client.replaceRealmRoles(USER_ID, Set.of("cashier", "manager"));

    // token, GET mappings, GET roles/manager, POST assign manager — no DELETE (nothing to
    // remove: cashier stays), and no re-lookup/re-add of cashier (already held).
    assertThat(server.getRequestCount()).isEqualTo(4);

    RecordedRequest tokenReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(tokenReq.getPath()).endsWith("/protocol/openid-connect/token");

    RecordedRequest fetchReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(fetchReq.getMethod()).isEqualTo("GET");
    assertThat(fetchReq.getPath()).endsWith("/role-mappings/realm");

    RecordedRequest roleLookupReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(roleLookupReq.getMethod()).isEqualTo("GET");
    assertThat(roleLookupReq.getPath()).endsWith("/roles/manager");

    RecordedRequest addReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(addReq.getMethod()).isEqualTo("POST");
    assertThat(addReq.getPath()).endsWith("/role-mappings/realm");
    assertThat(addReq.getBody().readUtf8()).contains("manager");

    assertThat(server.getRequestCount()).isEqualTo(4);
  }

  @Test
  void aFailedAddNeverStripsTheRolesTheUserAlreadyHeld() throws Exception {
    // OLD (remove-then-add) code would DELETE cashier first, then fail to add manager, leaving the
    // user with NO roles. The fixed ADD-before-REMOVE code must never send that DELETE.
    currentMappingsJson = "[{\"id\":\"c\",\"name\":\"cashier\"}]";
    failRoleLookupFor.add("manager");

    assertThatThrownBy(() -> client.replaceRealmRoles(USER_ID, Set.of("manager")))
        .isInstanceOf(KeycloakAdminException.class);

    // token, GET mappings, GET roles/manager (500) — the add never got to POST, and critically no
    // DELETE was ever sent, so cashier is preserved.
    assertThat(server.getRequestCount()).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
      assertThat(req.getMethod()).isNotEqualTo("DELETE");
    }
  }

  @Test
  void removesARoleNoLongerWantedWhenNoAddIsNeeded() throws Exception {
    currentMappingsJson =
        "[{\"id\":\"c\",\"name\":\"cashier\"},{\"id\":\"m\",\"name\":\"manager\"}]";

    client.replaceRealmRoles(USER_ID, Set.of("manager"));

    // token, GET mappings, DELETE cashier — manager already held, so no add call at all.
    assertThat(server.getRequestCount()).isEqualTo(3);

    server.takeRequest(1, TimeUnit.SECONDS); // token
    server.takeRequest(1, TimeUnit.SECONDS); // GET mappings

    RecordedRequest deleteReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(deleteReq.getMethod()).isEqualTo("DELETE");
    assertThat(deleteReq.getPath()).endsWith("/role-mappings/realm");
    assertThat(deleteReq.getBody().readUtf8()).contains("cashier");
  }

  @Test
  void whenBothAreNeededTheAddIsDispatchedBeforeTheRemove() throws Exception {
    currentMappingsJson =
        "[{\"id\":\"c\",\"name\":\"cashier\"},{\"id\":\"w\",\"name\":\"waitress\"}]";

    client.replaceRealmRoles(USER_ID, Set.of("cashier", "manager"));

    // token, GET mappings, GET roles/manager, POST assign manager, DELETE waitress.
    assertThat(server.getRequestCount()).isEqualTo(5);

    server.takeRequest(1, TimeUnit.SECONDS); // token
    server.takeRequest(1, TimeUnit.SECONDS); // GET mappings
    server.takeRequest(1, TimeUnit.SECONDS); // GET roles/manager

    RecordedRequest addReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(addReq.getMethod()).isEqualTo("POST");
    assertThat(addReq.getPath()).endsWith("/role-mappings/realm");

    RecordedRequest removeReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(removeReq.getMethod()).isEqualTo("DELETE");
    assertThat(removeReq.getPath()).endsWith("/role-mappings/realm");
    assertThat(removeReq.getBody().readUtf8()).contains("waitress");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
