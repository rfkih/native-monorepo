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
 * Proves the anonymous per-client-IP token bucket on the PUBLIC {@code /api/v1/signup} route
 * returns {@code 429} once exhausted — the throttle the tenant bucket cannot provide (no JWT).
 *
 * <p>{@code trust-forwarded-for} is enabled here and every request carries a test-unique {@code
 * X-Forwarded-For} IP, for two reasons: it exercises the trusted-ingress resolution path, and it
 * keys each test to its own bucket in the shared Redis container so this class can never exhaust
 * the {@code 127.0.0.1} bucket other test classes' signup requests key on. The spoof-safe default
 * (header IGNORED when untrusted) is proven in {@code AnonymousRateLimitFilterTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "native.gateway.rate-limit.signup.capacity=3",
      "native.gateway.rate-limit.signup.refill-tokens=1",
      "native.gateway.rate-limit.signup.refill-period=60s",
      "native.gateway.rate-limit.signup.trust-forwarded-for=true"
    })
class GatewaySignupRateLimitTest extends GatewayIntegrationTestBase {

  @Test
  void exceedingTheSignupBucketReturns429WithRetryAfter() {
    String clientIp = "203.0.113.7";

    // The bucket holds 3 tokens — three signups from this IP go through to the stub.
    for (int i = 0; i < 3; i++) {
      assertThat(postSignup(clientIp).status()).isEqualTo(HttpStatus.OK);
    }

    // The fourth is short-circuited at the gateway with 429 + Retry-After.
    Exchanged rejected = postSignup(clientIp);
    assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(rejected.retryAfter()).isNotNull();
    assertThat(Integer.parseInt(rejected.retryAfter())).isGreaterThanOrEqualTo(1);

    // Exactly the three allowed requests crossed the edge; the rejected one never did.
    assertThat(receivedRequests).hasSize(3);
  }

  @Test
  void aDifferentClientIpGetsItsOwnBucket() {
    // Exhaust one IP's bucket ...
    String firstIp = "203.0.113.20";
    for (int i = 0; i < 3; i++) {
      postSignup(firstIp);
    }
    assertThat(postSignup(firstIp).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // ... a different client IP is unaffected.
    assertThat(postSignup("203.0.113.21").status()).isEqualTo(HttpStatus.OK);
  }

  private Exchanged postSignup(String forwardedForIp) {
    RestClient client = gatewayClient();
    return client
        .post()
        .uri("/api/v1/signup")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Forwarded-For", forwardedForIp)
        .body("{}")
        .exchange(
            (req, res) ->
                new Exchanged(
                    HttpStatus.valueOf(res.getStatusCode().value()),
                    res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
  }

  private record Exchanged(HttpStatus status, String retryAfter) {}
}
