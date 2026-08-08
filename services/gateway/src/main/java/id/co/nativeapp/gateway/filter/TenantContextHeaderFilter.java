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
 * anything without a valid token with {@code 401}). It reads the {@code company_id} claim (the
 * login's ALLOWED COMPANIES — {@code string | string[]}, multi-company ownership ADR 0021), {@code
 * sub}, and roles from the JWT in the {@link SecurityContextHolder} and writes them as:
 *
 * <ul>
 *   <li>{@code X-Company-Id} — the resolved ACTIVE company (what the services' tenant binding
 *       reads),
 *   <li>{@code X-Actor} — the {@code sub} (the acting principal),
 *   <li>{@code X-Roles} — the comma-joined role names,
 *   <li>{@code X-Actor-Type} — {@code device | user} (ADR 0049), from the {@code actor_type} claim,
 *       defaulting to {@code "user"} when absent.
 * </ul>
 *
 * <p><strong>Active-company selection.</strong> A multi-company login picks its active company per
 * request by sending {@code X-Company-Id}. The inbound value is honoured ONLY when it is in the
 * token's allowed set — a value outside the set is rejected with {@code 403} (an authenticated
 * caller asking for a tenant it does not belong to). Absent a selection, the first allowed company
 * is the default, so a single-company login behaves exactly as before.
 *
 * <p><strong>Spoof defence.</strong> Any inbound {@code X-Company-Id} / {@code X-Actor} / {@code
 * X-Roles} / {@code X-Actor-Type} from the client is removed before the JWT-derived values are set:
 * {@code X-Company-Id} only survives as the VALIDATED selection, never verbatim; the headers
 * leaving the gateway are derived solely from the verified token plus the token-validated
 * selection. A client can therefore never self-declare {@code X-Actor-Type: user} to dodge a future
 * operator-required guard (ADR 0049 P4).
 *
 * <p>If the token carries NO companies the request is rejected with {@code 403} (authenticated but
 * not authorized for any tenant-scoped service — §1.1), UNLESS this instance was built {@link
 * #tenantOptional()}: the bootstrap routes ({@code /api/v1/companies/**}) must admit a 0-company
 * login so it can create its first company / list its (empty) memberships — the request is then
 * forwarded WITHOUT {@code X-Company-Id} and the downstream service's own per-path tenant-optional
 * enforcement decides (org-service permits exactly {@code POST /api/v1/companies} + {@code GET
 * /api/v1/companies/mine} unbound; everything else still 403s at the service edge).
 */
public final class TenantContextHeaderFilter
    implements HandlerFilterFunction<ServerResponse, ServerResponse> {

  /**
   * Tenant id header: inbound = the client's active-company selection; outbound = the resolved
   * tenant.
   */
  public static final String COMPANY_HEADER = "X-Company-Id";

  /** Acting principal header (the JWT {@code sub}). */
  public static final String ACTOR_HEADER = "X-Actor";

  /** Comma-joined granted roles. */
  public static final String ROLES_HEADER = "X-Roles";

  /**
   * Actor-type header (ADR 0049): {@code device | user}, gateway-injected from the JWT {@code
   * actor_type} claim ({@link TenantJwtAuthoritiesConverter#extractActorType}), defaulting to
   * {@code "user"} when the claim is absent — inert until ADR 0049 P3 provisions per-outlet kiosk
   * ({@code device}) logins. On the strip list ({@link #managedHeaders()}) exactly like {@link
   * #ACTOR_HEADER}/{@link #ROLES_HEADER}, so a client can never self-declare {@code user} to dodge
   * a future operator-required guard (ADR 0049 P4).
   */
  public static final String ACTOR_TYPE_HEADER = "X-Actor-Type";

  /** Whether a 0-company token may pass through (tenant-less) instead of being 403'd. */
  private final boolean tenantOptional;

  /** The default, tenant-REQUIRED filter (every business route). */
  public TenantContextHeaderFilter() {
    this(false);
  }

  private TenantContextHeaderFilter(boolean tenantOptional) {
    this.tenantOptional = tenantOptional;
  }

  /**
   * The tenant-OPTIONAL variant for the company-bootstrap routes: a 0-company login is forwarded
   * tenant-less (actor + roles only) so org-service's own tenant-optional matcher can admit exactly
   * the bootstrap endpoints. Tokens WITH companies behave identically to the required variant.
   */
  public static TenantContextHeaderFilter tenantOptional() {
    return new TenantContextHeaderFilter(true);
  }

  @Override
  public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
      throws Exception {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      // Defence in depth: the security chain should already have rejected this, but never forward
      // a request we cannot attribute to a validated token.
      return ServerResponse.status(401).build();
    }

    List<String> allowed = TenantJwtAuthoritiesConverter.extractCompanyIds(jwt);
    String actor = jwt.getSubject();
    String roles = String.join(",", TenantJwtAuthoritiesConverter.extractRoles(jwt));
    String actorType = TenantJwtAuthoritiesConverter.extractActorType(jwt);

    if (allowed.isEmpty()) {
      if (!tenantOptional) {
        // A valid token with no tenant claim is authenticated but NOT authorized to reach a
        // tenant-scoped service: a tenant/authZ denial is 403, not 401 (§1.1).
        return ServerResponse.status(403).build();
      }
      // Bootstrap: forward tenant-less (actor + roles only); the service edge enforces per path.
      ServerRequest tenantless =
          ServerRequest.from(request)
              .headers(
                  headers -> {
                    headers.remove(COMPANY_HEADER);
                    headers.remove(ACTOR_HEADER);
                    headers.remove(ROLES_HEADER);
                    headers.remove(ACTOR_TYPE_HEADER);
                    headers.set(ACTOR_HEADER, actor);
                    headers.set(ROLES_HEADER, roles);
                    headers.set(ACTOR_TYPE_HEADER, actorType);
                  })
              .build();
      return next.handle(tenantless);
    }

    // Resolve the ACTIVE company: the client's selection if (and only if) the token allows it,
    // else the token's first company. A selection outside the allowed set is an authenticated
    // caller requesting a tenant it does not belong to — reject, never silently fall back.
    String requested = request.headers().firstHeader(COMPANY_HEADER);
    String active;
    if (requested == null || requested.isBlank()) {
      active = allowed.getFirst();
    } else if (allowed.contains(requested)) {
      active = requested;
    } else {
      return ServerResponse.status(403).build();
    }

    ServerRequest trusted =
        ServerRequest.from(request)
            // Strip any client-supplied copies BEFORE setting the trusted values.
            .headers(
                headers -> {
                  headers.remove(COMPANY_HEADER);
                  headers.remove(ACTOR_HEADER);
                  headers.remove(ROLES_HEADER);
                  headers.remove(ACTOR_TYPE_HEADER);
                  headers.set(COMPANY_HEADER, active);
                  headers.set(ACTOR_HEADER, actor);
                  headers.set(ROLES_HEADER, roles);
                  headers.set(ACTOR_TYPE_HEADER, actorType);
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

  /**
   * The header names this filter manages — strips inbound, sets from the JWT. {@link
   * AnonymousTenantHeaderStripFilter} reuses this list so the anonymous self-order route strips the
   * SAME headers (never diverges) — including {@link #ACTOR_TYPE_HEADER}, so an anonymous caller
   * can never inject {@code X-Actor-Type} either. Deliberately does NOT include {@code
   * X-Operator-Session} (ADR 0049 P2): that header is not gateway-managed — it passes through
   * untouched (there is no global unknown-header strip in this gateway) for restaurant-service's
   * {@code OperatorSessionFilter} to verify OFFLINE.
   */
  public static List<String> managedHeaders() {
    return List.of(COMPANY_HEADER, ACTOR_HEADER, ROLES_HEADER, ACTOR_TYPE_HEADER);
  }
}
