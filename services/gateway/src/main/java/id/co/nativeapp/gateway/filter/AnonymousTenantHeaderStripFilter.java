package id.co.nativeapp.gateway.filter;

import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Strips client-supplied tenant-context headers ({@code X-Company-Id}/{@code X-Actor}/{@code
 * X-Roles}) on the gateway's one anonymous business route ({@code /api/v1/self-order/**}, Phase 6
 * self-order QR, ADR 0029) — WITHOUT requiring, or setting, a validated JWT.
 *
 * <p>{@link TenantContextHeaderFilter} cannot be reused here: it reads the security context for a
 * validated {@code Jwt} and returns {@code 401} when there is none — exactly the outcome an
 * anonymous route must never produce. This filter is the "strip" half only, reusing {@link
 * TenantContextHeaderFilter#managedHeaders()} so the two lists can never drift apart: an anonymous
 * caller can never inject a company/actor/roles header for the downstream to trust. The route also
 * never claims to establish a tenant identity — restaurant-service resolves the self-order session
 * strictly from the QR-issued {@code X-Self-Order-Token} (left untouched by this filter), never
 * from these headers.
 */
public final class AnonymousTenantHeaderStripFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    ServerRequest stripped =
        ServerRequest.from(request)
            .headers(headers -> TenantContextHeaderFilter.managedHeaders().forEach(headers::remove))
            .build();
    return next.handle(stripped);
  }
}
