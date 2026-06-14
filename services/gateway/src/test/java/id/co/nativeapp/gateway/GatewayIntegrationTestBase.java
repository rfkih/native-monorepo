package id.co.nativeapp.gateway;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared container fixture for the gateway integration tests.
 *
 * <p>Stands up the real collaborators the gateway depends on:
 *
 * <ul>
 *   <li><strong>Keycloak</strong> (dasniko Testcontainers) importing {@code native-realm.json} — it
 *       issues genuine RS256 tokens, so signature/issuer/JWKS validation is exercised end to end,
 *       not mocked;
 *   <li><strong>Redis</strong> (a plain {@code redis:7-alpine} GenericContainer) — the real
 *       distributed token bucket, so the {@code 429} proof is genuine;
 *   <li><strong>MockWebServer</strong> — a lightweight stub downstream, so header injection and the
 *       spoof-strip are asserted without booting another Native service.
 * </ul>
 *
 * <p>The gateway's issuer/JWKS, Redis host/port, and all three route targets are wired to these
 * containers via {@link DynamicPropertySource}; every route points at the single MockWebServer so a
 * routed request lands there for inspection.
 *
 * <p><strong>Downstream stub model.</strong> The stub installs a {@link Dispatcher} that always
 * answers {@code 200 "ok"} and <em>records</em> every request it actually receives into {@link
 * #receivedRequests}. This is deliberately not the {@code enqueue}/{@code takeRequest} FIFO model:
 * the static {@link MockWebServer} is shared across every {@code @SpringBootTest} class in the JVM,
 * so a FIFO queue makes assertions order-dependent and lets one test drain another's response (a
 * routed request then hits an empty queue and the proxy returns {@code 500}). With the always-OK
 * dispatcher, a routed request can never 500 for lack of a queued response, and "was/was-not
 * forwarded" is asserted against the recorded list (cleared per test in {@link
 * #resetDownstream()}), not a shared FIFO. Each test therefore proves exactly what crossed the edge
 * regardless of order.
 */
@Testcontainers
abstract class GatewayIntegrationTestBase {

  protected static final String REALM = "native";
  protected static final String CLIENT_ID = "native-gateway";
  protected static final String CLIENT_SECRET = "native-gateway-secret";
  protected static final String USERNAME = "owner-acme";
  protected static final String PASSWORD = "owner-password";
  protected static final String EXPECTED_COMPANY_ID = "11111111-1111-1111-1111-111111111111";

  // A valid user in the `native` realm WITHOUT a company_id attribute → token is authentic but
  // tenant-less, so the gateway must reject it with 403 (tenant/authZ denial), not route it.
  protected static final String TENANTLESS_USERNAME = "tenantless-user";
  protected static final String TENANTLESS_PASSWORD = "tenantless-password";

  // A client with a 1s access-token lifespan, used to prove the EXPIRED-token 401.
  protected static final String SHORTLIVED_CLIENT_ID = "native-gateway-shortlived";
  protected static final String SHORTLIVED_CLIENT_SECRET = "native-gateway-shortlived-secret";

  // A SECOND realm (a different issuer); a token it mints must be rejected 401 (wrong issuer).
  protected static final String OTHER_REALM = "other";
  protected static final String OTHER_CLIENT_ID = "other-client";
  protected static final String OTHER_CLIENT_SECRET = "other-client-secret";
  protected static final String OTHER_USERNAME = "other-user";
  protected static final String OTHER_PASSWORD = "other-password";

  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFiles("/native-realm.json", "/other-realm.json");

  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  /**
   * The Redis the gateway is wired to. Defaults to the shared {@link #REDIS}; a subclass that needs
   * a Redis it can take down (the fail-closed proof) points this at its own container in a
   * {@code @BeforeAll} (which runs after this base's {@code @BeforeAll} and before the context's
   * dynamic properties are evaluated, so the gateway binds to the right host/port). This
   * indirection is used because two {@code @DynamicPropertySource} methods registering the same key
   * have no guaranteed order, so a subclass cannot reliably "win" by re-registering it.
   */
  protected static GenericContainer<?> activeRedis = REDIS;

  protected static MockWebServer downstream;

  /**
   * Every request the downstream stub actually received, in arrival order. Cleared before each test
   * ({@link #resetDownstream()}) so "was/was-not forwarded" assertions are order-independent across
   * the shared static stub.
   */
  protected static final List<RecordedRequest> receivedRequests = new CopyOnWriteArrayList<>();

  private static final OkHttpClient HTTP = new OkHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort protected int gatewayPort;

  /**
   * Singleton-container start: Keycloak, Redis, and the stub are started ONCE for the whole JVM and
   * deliberately never stopped per class (Ryuk reaps them at JVM exit). Stopping/restarting the
   * shared Keycloak between classes would change its mapped port while a cached Spring context's
   * JWKS decoder still points at the old one → {@code Connection refused} fetching the key set. The
   * idempotent guard makes this safe to call from every class's {@code @BeforeAll}.
   */
  @BeforeAll
  static synchronized void startContainers() throws IOException {
    KEYCLOAK.start();
    REDIS.start();
    // Default the gateway at the shared Redis. A subclass that needs a takedownable Redis overrides
    // this in its OWN @BeforeAll, which (superclass-before-subclass) runs after this reset — so a
    // prior class's redirect can never leak into the next class.
    activeRedis = REDIS;
    if (downstream == null) {
      MockWebServer server = new MockWebServer();
      // Always answer 200 "ok" and record what crossed the edge — see the class javadoc for why
      // this replaces the order-fragile enqueue/takeRequest FIFO on the shared static stub.
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              receivedRequests.add(request);
              return new MockResponse().setResponseCode(200).setBody("ok");
            }
          });
      server.start();
      downstream = server;
    }
  }

  @BeforeEach
  void resetDownstream() {
    receivedRequests.clear();
  }

  /** The single request the stub received (fails if zero or more than one were forwarded). */
  protected static RecordedRequest theForwardedRequest() {
    if (receivedRequests.size() != 1) {
      throw new AssertionError(
          "expected exactly one forwarded request but got " + receivedRequests.size());
    }
    return receivedRequests.getFirst();
  }

  @DynamicPropertySource
  static void wireProperties(DynamicPropertyRegistry registry) {
    String issuer = KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM;
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> issuer + "/protocol/openid-connect/certs");

    // Read through `activeRedis` (not REDIS directly) so a subclass can redirect the gateway at a
    // Redis it controls; the supplier is evaluated at context load, after every @BeforeAll has run.
    registry.add("spring.data.redis.host", () -> activeRedis.getHost());
    registry.add("spring.data.redis.port", () -> activeRedis.getMappedPort(6379));

    // Every route targets the single MockWebServer stub so a routed request is observable.
    java.util.function.Supplier<Object> stubUri =
        () -> "http://" + downstream.getHostName() + ":" + downstream.getPort();
    registry.add("native.gateway.routes.org-service", stubUri);
    registry.add("native.gateway.routes.restaurant-service", stubUri);
    registry.add("native.gateway.routes.finance-service", stubUri);
  }

  /**
   * Obtains a real RS256 access token from Keycloak via the password (direct access) grant — the
   * default owner-acme token in the {@code native} realm (carries {@code company_id} + roles).
   */
  protected static String obtainAccessToken() throws IOException {
    return obtainAccessToken(REALM, CLIENT_ID, CLIENT_SECRET, USERNAME, PASSWORD);
  }

  /**
   * Obtains a token for the given realm/client/user via the password grant — so a test can mint a
   * tenant-less token, a short-lived token, or a foreign-issuer token from a second realm.
   */
  protected static String obtainAccessToken(
      String realm, String clientId, String clientSecret, String username, String password)
      throws IOException {
    String tokenUrl =
        KEYCLOAK.getAuthServerUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
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

  /** A RestClient pointed at the running gateway. */
  protected RestClient gatewayClient() {
    return RestClient.builder().baseUrl("http://localhost:" + gatewayPort).build();
  }

  protected static String bearer(String token) {
    return "Bearer " + token;
  }
}
