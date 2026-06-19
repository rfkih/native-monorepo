package id.co.nativeapp.gateway.filter;

import id.co.nativeapp.gateway.security.TenantJwtAuthoritiesConverter;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Per-route role gate. Forwards only if the validated token carries at least one of the route's
 * allowed business roles; otherwise it stops the request at the edge with {@code 403}.
 *
 * <p>This is how the two surfaces are kept apart at the API, not just in the UI: the cashier POS
 * routes ({@code /api/v1/menu}, {@code /api/v1/orders}, {@code /api/v1/sales}) allow {@code owner}
 * / {@code manager} / {@code cashier}, while the owner-dashboard routes ({@code /api/v1/revenue},
 * {@code /api/v1/pnl}, {@code /api/v1/companies}) allow only {@code owner} / {@code manager}. A
 * cashier token therefore cannot read the dashboard's financials even if a UI guard were bypassed —
 * the gate fails closed.
 *
 * <p>Roles are taken from the VERIFIED token via {@link TenantJwtAuthoritiesConverter#extractRoles}
 * (the same curation the header injector uses), never from a client header. A valid token with no
 * matching role is authenticated but not authorized → {@code 403} (ENGINEERING-STANDARDS §1.1); a
 * missing/invalid token never reaches this filter (the security chain already returned {@code
 * 401}).
 */
public final class RoleAuthorizationFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  private final Set<String> allowedRoles;

  public RoleAuthorizationFilter(String... allowedRoles) {
    this.allowedRoles = Set.of(allowedRoles);
  }

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      // Defence in depth: the security chain should already have rejected this with 401.
      return ServerResponse.status(401).build();
    }
    List<String> roles = TenantJwtAuthoritiesConverter.extractRoles(jwt);
    boolean permitted = roles.stream().anyMatch(allowedRoles::contains);
    if (!permitted) {
      return ServerResponse.status(403).build();
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
