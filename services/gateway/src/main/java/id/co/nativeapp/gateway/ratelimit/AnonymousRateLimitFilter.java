package id.co.nativeapp.gateway.ratelimit;

import id.co.nativeapp.gateway.config.RateLimitProperties;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Anonymous per-client-IP rate-limit filter for the gateway's unauthenticated routes — the PUBLIC
 * sign-up route and the PUBLIC self-order QR route ({@code /api/v1/self-order/**}, Phase 6, ADR
 * 0029) — rejecting a client IP that exceeds its bucket with {@code 429 Too Many Requests}.
 *
 * <p>The tenant {@link RateLimitFilter} keys on the validated JWT's {@code (company_id, sub)} and
 * therefore cannot protect an unauthenticated endpoint. This filter keys on the client IP instead.
 * Each caller supplies its OWN {@link RateLimitProperties.AnonymousBucket} (its own knobs) and its
 * OWN {@code keyNamespace} — the namespace is load-bearing: it keeps every anonymous route's bucket
 * independent in Redis, so (for example) a busy dining room hammering self-order can never drain,
 * or be drained by, the signup bucket.
 *
 * <p><strong>IP resolution is spoof-safe by default.</strong> {@code X-Forwarded-For} is honored
 * ONLY when {@code trust-forwarded-for} is explicitly enabled (deployments behind a trusted ingress
 * that appends the real client IP), and then only its LAST entry — the one the trusted hop wrote.
 * Otherwise the socket's remote address is used and the header is ignored, so a direct client
 * cannot mint fresh buckets by varying a header.
 *
 * <p>The bucket itself (and its fail-closed Redis behavior) is {@link RedisTokenBucketRateLimiter}:
 * if Redis is down, the request is denied, never unmetered. A CAPTCHA / proof-of-work challenge at
 * the same edge remains a follow-up — this filter is the transport-level floor, not the whole bot
 * story.
 */
public final class AnonymousRateLimitFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  private final RedisTokenBucketRateLimiter limiter;
  private final RateLimitProperties.AnonymousBucket props;
  private final String keyNamespace;

  /**
   * @param keyNamespace the Redis key prefix for THIS route's bucket (e.g. {@code "anon:signup:"}
   *     or {@code "anon:self-order:"}) — must be unique per anonymous route so buckets never
   *     collide.
   */
  public AnonymousRateLimitFilter(
      RedisTokenBucketRateLimiter limiter,
      RateLimitProperties.AnonymousBucket props,
      String keyNamespace) {
    this.limiter = limiter;
    this.props = props;
    this.keyNamespace = keyNamespace;
  }

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    String clientIp = resolveClientIp(request);
    boolean allowed =
        limiter.tryAcquire(
            keyNamespace + clientIp, props.capacity(), props.refillTokens(), props.refillPeriod());
    if (!allowed) {
      return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(
              HttpHeaders.RETRY_AFTER,
              Long.toString(limiter.retryAfterSeconds(props.refillPeriod())))
          .build();
    }
    return next.handle(request);
  }

  /**
   * The bucket-keying client IP: the LAST {@code X-Forwarded-For} entry when (and only when) the
   * deployment declares a trusted ingress; otherwise the socket's remote address.
   *
   * <p>ALL {@code X-Forwarded-For} header lines are flattened before taking the last entry — a
   * trusted ingress may append its hop as a separate header line rather than extending the first,
   * and reading only the first line would hand a client-controlled value to the bucket key
   * (security review, signup hardening).
   */
  private String resolveClientIp(ServerRequest request) {
    if (props.trustForwardedFor()) {
      List<String> headerLines = request.headers().header("X-Forwarded-For");
      for (int i = headerLines.size() - 1; i >= 0; i--) {
        String[] hops = headerLines.get(i).split(",");
        for (int j = hops.length - 1; j >= 0; j--) {
          String hop = hops[j].trim();
          if (!hop.isEmpty()) {
            return hop;
          }
        }
      }
    }
    return request.servletRequest().getRemoteAddr();
  }
}
