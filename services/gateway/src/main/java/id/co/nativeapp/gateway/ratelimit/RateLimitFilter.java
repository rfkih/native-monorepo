package id.co.nativeapp.gateway.ratelimit;

import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import id.co.nativeapp.gateway.security.TenantJwtAuthoritiesConverter;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Per-tenant rate-limit filter — rejects a request that exceeds the {@code (company_id, sub)} token
 * bucket with {@code 429 Too Many Requests}.
 *
 * <p>It runs after authentication (and before {@link TenantContextHeaderFilter}), so the {@code
 * company_id} and {@code sub} that key the bucket come from the validated JWT (never a client
 * header). A missing/invalid token never reaches here — the security chain already rejected it with
 * {@code 401}. A <em>valid</em> token that lacks a {@code company_id} claim is authenticated but
 * not authorized to reach a tenant-scoped service, so it is a {@code 403} tenant/authZ denial
 * (§1.1), not a {@code 401}.
 *
 * <p>When the bucket is exhausted the response is {@code 429 Too Many Requests} with a {@code
 * Retry-After} header (seconds) so a well-behaved client backs off for the refill window.
 */
public final class RateLimitFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  private final RedisTokenBucketRateLimiter limiter;

  public RateLimitFilter(RedisTokenBucketRateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      // Defence in depth: the security chain should already have rejected an unauthenticated
      // request with 401 before any route filter runs.
      return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
    }
    // Key the bucket on the login's FIRST allowed company (multi-company ownership, ADR 0021 — the
    // claim is string|string[]; identical behavior for a single-company login). A 0-company token
    // buckets on the sub alone ("none") — tenant AUTHORIZATION is the TenantContextHeaderFilter's
    // decision (it knows per-route tenant-optionality); rate limiting only cares about abuse.
    List<String> companyIds = TenantJwtAuthoritiesConverter.extractCompanyIds(jwt);
    String companyKey = companyIds.isEmpty() ? "none" : companyIds.getFirst();
    String sub = jwt.getSubject();
    if (!limiter.tryAcquire(companyKey, sub)) {
      return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, Long.toString(limiter.retryAfterSeconds()))
          .build();
    }
    return next.handle(request);
  }

  private static Jwt currentJwt() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken();
    }
    return null;
  }
}
