package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * FIX 6a — proves the Redis-backed rate limiter FAILS CLOSED when its backend is unreachable.
 *
 * <p>This class brings up its OWN dedicated Redis (so stopping it cannot disturb the shared Redis
 * the sibling integration tests use) and redirects the gateway at it via the base's {@code
 * activeRedis} hook in a {@code @BeforeAll} (set before the context's dynamic properties are
 * evaluated, so the gateway binds to THIS Redis — robust against {@code @DynamicPropertySource}
 * ordering). A first request (with a real token, Redis up) is routed normally — the stub answers
 * {@code 200}. We then {@code stop()} the dedicated Redis: the limiter can no longer prove the
 * request is within budget, so it must DENY ({@code 429}) rather than fail open and let an
 * unbounded flood through with the limit silently disabled. The stub is never touched on the denied
 * request — the limiter short-circuits before the proxy runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// A unique marker so this class never shares a cached Spring context with a sibling that is wired
// to
// the shared Redis — this context must bind to the dedicated, takedownable Redis.
@TestPropertySource(properties = "native.gateway.test.fail-closed=true")
class GatewayRateLimitFailClosedTest extends GatewayIntegrationTestBase {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> DEDICATED_REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @BeforeAll
  static void redirectGatewayAtDedicatedRedis() {
    // Runs after the base @BeforeAll and before the gateway context's dynamic properties are read,
    // so the gateway's Redis host/port resolve to THIS container (which this test alone can stop).
    DEDICATED_REDIS.start();
    activeRedis = DEDICATED_REDIS;
  }

  @Test
  void withRedisDownTheLimiterFailsClosedAndReturns429NeverReachingTheDownstream()
      throws Exception {
    String token = obtainAccessToken();
    RestClient client = gatewayClient();

    // Sanity: with Redis UP the request is allowed and routed (stub records it and answers 200).
    HttpStatus up = sendOnce(client, token);
    assertThat(up).isEqualTo(HttpStatus.OK);
    assertThat(receivedRequests).hasSize(1);

    // Take the limiter's backend away.
    DEDICATED_REDIS.stop();
    receivedRequests.clear();

    // Now the limiter cannot reach Redis → it must FAIL CLOSED with 429 (deny), not fail open.
    // Poll briefly: the first post-stop call may surface the broken connection as the trigger.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(sendOnce(client, token)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

    // The denied requests never crossed to the downstream stub.
    assertThat(receivedRequests).isEmpty();
  }

  private HttpStatus sendOnce(RestClient client, String token) {
    return client
        .get()
        .uri("/api/v1/sales/x")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))
        .exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
  }
}
