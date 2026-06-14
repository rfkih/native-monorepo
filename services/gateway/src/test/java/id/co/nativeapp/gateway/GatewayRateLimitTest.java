package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Proves the Redis-backed per-tenant token bucket returns {@code 429} once exhausted.
 *
 * <p>A deliberately tiny bucket (capacity 2, slow refill) is configured for this class so the limit
 * is reached in a couple of requests. The same valid token (same {@code company_id} + {@code sub})
 * is reused, so every request keys the same bucket. No {@code Thread.sleep} — Awaitility polls for
 * the {@code 429} to appear within the refill window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "native.gateway.rate-limit.capacity=2",
      "native.gateway.rate-limit.refill-tokens=1",
      "native.gateway.rate-limit.refill-period=60s"
    })
class GatewayRateLimitTest extends GatewayIntegrationTestBase {

  @Test
  void exceedingTheTenantBucketReturns429() throws Exception {
    // The downstream stub always answers 200 when reached; a 429 means the gateway short-circuited
    // at the rate-limit filter (see GatewayIntegrationTestBase's recording dispatcher).
    String token = obtainAccessToken();
    RestClient client = gatewayClient();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              Exchanged exchanged = sendOnce(client, token);
              assertThat(exchanged.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              // FIX 6c: a 429 advertises a Retry-After (whole seconds) so a client backs off.
              assertThat(exchanged.retryAfter()).isNotNull();
              assertThat(Integer.parseInt(exchanged.retryAfter())).isGreaterThanOrEqualTo(1);
            });
  }

  private Exchanged sendOnce(RestClient client, String token) {
    return client
        .get()
        .uri("/api/v1/sales/x")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))
        .exchange(
            (req, res) ->
                new Exchanged(
                    HttpStatus.valueOf(res.getStatusCode().value()),
                    res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
  }

  private record Exchanged(HttpStatus status, String retryAfter) {}
}
