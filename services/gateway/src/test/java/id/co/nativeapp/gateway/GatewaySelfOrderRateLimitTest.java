package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Proves the anonymous per-client-IP token bucket on the PUBLIC {@code /api/v1/self-order/**} QR
 * route (Phase 6, ADR 0029) returns {@code 429} once exhausted — the throttle the tenant bucket
 * cannot provide (no JWT) — and that its bucket lives in its OWN Redis namespace, independent of
 * the signup route's.
 *
 * <p>{@code trust-forwarded-for} is enabled for BOTH buckets here and every request carries a
 * test-unique {@code X-Forwarded-For} IP, exactly like {@code GatewaySignupRateLimitTest} — see
 * that class's javadoc for why (keys this class to its own buckets in the shared Redis container;
 * the spoof-safe default is proven once in {@code AnonymousRateLimitFilterTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "native.gateway.rate-limit.self-order.capacity=3",
      "native.gateway.rate-limit.self-order.refill-tokens=1",
      "native.gateway.rate-limit.self-order.refill-period=60s",
      "native.gateway.rate-limit.self-order.trust-forwarded-for=true",
      "native.gateway.rate-limit.signup.capacity=5",
      "native.gateway.rate-limit.signup.refill-tokens=1",
      "native.gateway.rate-limit.signup.refill-period=60s",
      "native.gateway.rate-limit.signup.trust-forwarded-for=true"
    })
class GatewaySelfOrderRateLimitTest extends GatewayIntegrationTestBase {

  @Test
  void exceedingTheSelfOrderBucketReturns429WithRetryAfter() {
    String clientIp = "203.0.113.100";

    // The bucket holds 3 tokens — three self-order requests from this IP go through to the stub.
    for (int i = 0; i < 3; i++) {
      assertThat(getSelfOrder(clientIp).status()).isEqualTo(HttpStatus.OK);
    }

    // The fourth is short-circuited at the gateway with 429 + Retry-After.
    Exchanged rejected = getSelfOrder(clientIp);
    assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(rejected.retryAfter()).isNotNull();
    assertThat(Integer.parseInt(rejected.retryAfter())).isGreaterThanOrEqualTo(1);

    // Exactly the three allowed requests crossed the edge; the rejected one never did.
    assertThat(receivedRequests).hasSize(3);
  }

  @Test
  void aDifferentClientIpGetsItsOwnBucket() {
    String firstIp = "203.0.113.101";
    for (int i = 0; i < 3; i++) {
      getSelfOrder(firstIp);
    }
    assertThat(getSelfOrder(firstIp).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // ... a different client IP is unaffected.
    assertThat(getSelfOrder("203.0.113.102").status()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void theSelfOrderBucketIsIndependentOfTheSignupBucketForTheSameIp() {
    // Exhaust self-order's bucket for one IP...
    String clientIp = "203.0.113.103";
    for (int i = 0; i < 3; i++) {
      assertThat(getSelfOrder(clientIp).status()).isEqualTo(HttpStatus.OK);
    }
    assertThat(getSelfOrder(clientIp).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // ... a signup request from the SAME IP is unaffected: separate Redis key namespaces
    // (anon:self-order: vs anon:signup:), never sharing or draining one another's budget.
    RestClient client = gatewayClient();
    HttpStatus signupStatus =
        client
            .post()
            .uri("/api/v1/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", clientIp)
            .body("{}")
            .exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
    assertThat(signupStatus).isEqualTo(HttpStatus.OK);
  }

  private Exchanged getSelfOrder(String forwardedForIp) {
    RestClient client = gatewayClient();
    return client
        .get()
        .uri("/api/v1/self-order/menu")
        .header("X-Forwarded-For", forwardedForIp)
        .exchange(
            (req, res) ->
                new Exchanged(
                    HttpStatus.valueOf(res.getStatusCode().value()),
                    res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
  }

  private record Exchanged(HttpStatus status, String retryAfter) {}
}
