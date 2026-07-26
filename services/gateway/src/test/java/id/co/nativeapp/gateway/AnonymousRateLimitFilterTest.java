package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.gateway.config.RateLimitProperties;
import id.co.nativeapp.gateway.ratelimit.AnonymousRateLimitFilter;
import id.co.nativeapp.gateway.ratelimit.RedisTokenBucketRateLimiter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Unit tests for {@link AnonymousRateLimitFilter}'s client-IP resolution and 429 mapping.
 *
 * <p>The critical property proven here is the <strong>spoof-safe default</strong>: when {@code
 * trust-forwarded-for} is off (no trusted ingress), a client-supplied {@code X-Forwarded-For}
 * header must be IGNORED — otherwise any client could mint fresh buckets per request and the limit
 * would be decorative. The Redis bucket behavior itself is covered by the integration tests; the
 * limiter is mocked here.
 */
class AnonymousRateLimitFilterTest {

  private static final Duration REFILL = Duration.ofHours(1);

  private final RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);

  @Test
  void untrustedForwardedForHeaderIsIgnoredAndTheSocketAddressKeysTheBucket() throws Exception {
    when(limiter.tryAcquire(startsWith("anon:signup:"), anyLong(), anyLong(), any()))
        .thenReturn(true);
    AnonymousRateLimitFilter filter = filter(false);

    ServerResponse response =
        filter.filter(request("10.0.0.9", "1.2.3.4, 5.6.7.8"), req -> ServerResponse.ok().build());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
    // The bucket key must come from the socket's remote address, never the spoofable header.
    verify(limiter).tryAcquire("anon:signup:10.0.0.9", 3L, 1L, REFILL);
  }

  @Test
  void trustedForwardedForUsesTheLastEntryTheIngressAppended() throws Exception {
    when(limiter.tryAcquire(startsWith("anon:signup:"), anyLong(), anyLong(), any()))
        .thenReturn(true);
    AnonymousRateLimitFilter filter = filter(true);

    filter.filter(request("10.0.0.9", "1.2.3.4, 203.0.113.50"), req -> ServerResponse.ok().build());

    // Earlier hops are client-controlled; only the LAST entry was written by the trusted ingress.
    verify(limiter).tryAcquire("anon:signup:203.0.113.50", 3L, 1L, REFILL);
  }

  @Test
  void trustedForwardedForFlattensMultipleHeaderLinesAndUsesTheOverallLastEntry() throws Exception {
    // An ingress may append its hop as a SEPARATE X-Forwarded-For line rather than extending the
    // client's; only the overall-last entry (the trusted hop) may key the bucket — reading just
    // the first line would hand a client-controlled value to the key (security review, LOW).
    when(limiter.tryAcquire(startsWith("anon:signup:"), anyLong(), anyLong(), any()))
        .thenReturn(true);
    AnonymousRateLimitFilter filter = filter(true);

    MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/v1/signup");
    servletRequest.setRemoteAddr("10.0.0.9");
    servletRequest.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8"); // client-controlled line
    servletRequest.addHeader("X-Forwarded-For", "203.0.113.60"); // appended by the trusted ingress
    filter.filter(
        ServerRequest.create(servletRequest, List.of()), req -> ServerResponse.ok().build());

    verify(limiter).tryAcquire("anon:signup:203.0.113.60", 3L, 1L, REFILL);
  }

  @Test
  void anExhaustedBucketMapsTo429WithRetryAfter() throws Exception {
    when(limiter.tryAcquire(startsWith("anon:signup:"), anyLong(), anyLong(), any()))
        .thenReturn(false);
    when(limiter.retryAfterSeconds(REFILL)).thenReturn(3600L);
    AnonymousRateLimitFilter filter = filter(false);

    ServerResponse response =
        filter.filter(request("10.0.0.9", null), req -> ServerResponse.ok().build());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
  }

  private AnonymousRateLimitFilter filter(boolean trustForwardedFor) {
    return new AnonymousRateLimitFilter(
        limiter, new RateLimitProperties.Signup(3, 1, REFILL, trustForwardedFor));
  }

  private static ServerRequest request(String remoteAddr, String forwardedFor) {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/v1/signup");
    servletRequest.setRemoteAddr(remoteAddr);
    if (forwardedFor != null) {
      servletRequest.addHeader("X-Forwarded-For", forwardedFor);
    }
    return ServerRequest.create(servletRequest, List.of());
  }
}
