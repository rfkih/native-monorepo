package id.co.nativeapp.gateway.filter;

import id.co.nativeapp.gateway.security.TenantJwtAuthoritiesConverter;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Injects the validated tenant context onto the outbound (downstream) request as trusted headers,
 * and strips any client-supplied copies first so a caller can never spoof its tenant.
 *
 * <p>By the time this filter runs the request is authenticated (the security filter chain rejected
 * anything without a valid token with {@code 401}). It reads the {@code company_id} claim, {@code
 * sub}, and roles from the JWT in the {@link SecurityContextHolder} and writes them as:
 *
 * <ul>
 *   <li>{@code X-Company-Id} — the {@code company_id} claim (the tenant; what the services' {@code
 *       DevTenantFilter} reads),
 *   <li>{@code X-Actor} — the {@code sub} (the acting principal),
 *   <li>{@code X-Roles} — the comma-joined role names.
 * </ul>
 *
 * <p><strong>Spoof defence.</strong> Any inbound {@code X-Company-Id} / {@code X-Actor} / {@code
 * X-Roles} from the client is removed before the JWT-derived values are set, so a client header can
 * never reach a downstream service. The headers leaving the gateway are derived solely from the
 * verified token.
 *
 * <p>If the {@code company_id} claim is absent the request is rejected with {@code 403} rather than
 * forwarded tenant-less: the token is valid (so it is not a {@code 401} authentication failure) but
 * carries no tenant, so it is not authorized to reach a tenant-scoped service — a tenant/authZ
 * denial maps to {@code 403} (ENGINEERING-STANDARDS §1.1). A missing/invalid/expired token never
 * reaches this filter; the security chain already rejected it with {@code 401}.
 */
public final class TenantContextHeaderFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  /** Tenant id header consumed by every service's {@code DevTenantFilter}. */
  public static final String COMPANY_HEADER = "X-Company-Id";

  /** Acting principal header (the JWT {@code sub}). */
  public static final String ACTOR_HEADER = "X-Actor";

  /** Comma-joined granted roles. */
  public static final String ROLES_HEADER = "X-Roles";

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      // Defence in depth: the security chain should already have rejected this, but never forward
      // a request we cannot attribute to a validated token.
      return ServerResponse.status(401).build();
    }

    String companyId = jwt.getClaimAsString(TenantJwtAuthoritiesConverter.COMPANY_ID_CLAIM);
    if (companyId == null || companyId.isBlank()) {
      // A valid token with no tenant claim is authenticated but NOT authorized to reach a
      // tenant-scoped service: a tenant/authZ denial is 403, not 401 (§1.1).
      return ServerResponse.status(403).build();
    }
    String actor = jwt.getSubject();
    String roles = String.join(",", TenantJwtAuthoritiesConverter.extractRoles(jwt));

    ServerRequest trusted =
        ServerRequest.from(request)
            // Strip any client-supplied copies BEFORE setting the trusted values.
            .headers(
                headers -> {
                  headers.remove(COMPANY_HEADER);
                  headers.remove(ACTOR_HEADER);
                  headers.remove(ROLES_HEADER);
                  headers.set(COMPANY_HEADER, companyId);
                  headers.set(ACTOR_HEADER, actor);
                  headers.set(ROLES_HEADER, roles);
                })
            .build();

    return next.handle(trusted);
  }

  private static Jwt currentJwt() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken();
    }
    return null;
  }

  /** The header names this filter manages — strips inbound, sets from the JWT. */
  public static List<String> managedHeaders() {
    return List.of(COMPANY_HEADER, ACTOR_HEADER, ROLES_HEADER);
  }
}
