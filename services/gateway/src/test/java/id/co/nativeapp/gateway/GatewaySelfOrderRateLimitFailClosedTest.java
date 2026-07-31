package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the anonymous self-order QR route's rate limiter FAILS CLOSED when its Redis backend is
 * unreachable (Phase 6, ADR 0029) — the same fail-closed guarantee {@code
 * GatewayRateLimitFailClosedTest} proves for the authenticated per-tenant bucket. An anonymous
 * route has no JWT and no authenticated caller to fall back on, so this proof matters just as much
 * here: a Redis outage must DENY self-order traffic, never wave it through unmetered.
 *
 * <p>This class brings up its OWN dedicated Redis (so stopping it cannot disturb the shared Redis
 * the sibling integration tests use) and redirects the gateway at it via the base's {@code
 * activeRedis} hook in a {@code @BeforeAll} — see {@code GatewayRateLimitFailClosedTest}'s javadoc
 * for the full mechanics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// A unique marker so this class never shares a cached Spring context with a sibling that is wired
// to the shared Redis — this context must bind to the dedicated, takedownable Redis.
@TestPropertySource(properties = "native.gateway.test.self-order-fail-closed=true")
class GatewaySelfOrderRateLimitFailClosedTest extends GatewayIntegrationTestBase {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> DEDICATED_REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @BeforeAll
  static void redirectGatewayAtDedicatedRedis() {
    DEDICATED_REDIS.start();
    activeRedis = DEDICATED_REDIS;
  }

  @Test
  void withRedisDownTheSelfOrderLimiterFailsClosedAndReturns429NeverReachingTheDownstream() {
    RestClient client = gatewayClient();

    // Sanity: with Redis UP the anonymous request is allowed and routed (no bearer token needed).
    HttpStatus up = sendOnce(client);
    assertThat(up).isEqualTo(HttpStatus.OK);
    assertThat(receivedRequests).hasSize(1);

    // Take the limiter's backend away.
    DEDICATED_REDIS.stop();
    receivedRequests.clear();

    // Now the limiter cannot reach Redis → it must FAIL CLOSED with 429 (deny), never fail open —
    // there is no authenticated caller to fall back to trusting on this route.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(sendOnce(client)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

    // The denied requests never crossed to the downstream stub.
    assertThat(receivedRequests).isEmpty();
  }

  private HttpStatus sendOnce(RestClient client) {
    return client
        .get()
        .uri("/api/v1/self-order/menu")
        .exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
  }
}
