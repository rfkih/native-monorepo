package id.co.nativeapp.security;

import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} from a <em>locally validated</em> JWT — the defense-in-depth heart of
 * #16. By the time this filter runs in the non-dev chain ({@link JwtSecurityConfig}) the inbound
 * RS256 token has already been signature/issuer/expiry-checked against Keycloak's JWKS by the
 * resource-server decoder, and a missing/invalid/expired token was already rejected with {@code
 * 401} — so this filter only sees authenticated requests.
 *
 * <p>It reads the tenant ({@code company_id} claim) and actor ({@code sub}) <strong>from the
 * token's claims only</strong> and wraps the rest of the chain in {@link
 * TenantContext#callAs(String, String, java.util.concurrent.Callable)}, exactly where the dev
 * {@code DevTenantFilter} binds from trusted headers. The auto-RLS aspect then sets {@code
 * app.current_tenant} on every {@code @Transactional} unit of work, so row-level security keys on
 * the verified tenant.
 *
 * <p><strong>It never trusts an inbound {@code X-Company-Id} (or any client header).</strong> The
 * tenant comes solely from the verified token, so a forged {@code X-Company-Id} sent straight to a
 * service port cannot bind an arbitrary tenant. This is the gap the gateway alone could not close.
 *
 * <p>A valid token that carries no {@code company_id} claim is authenticated but not authorized to
 * reach a tenant-scoped service: it is rejected with {@code 403} as an RFC-7807 {@link
 * ProblemDetail} ({@code application/problem+json}), mirroring the gateway's {@code
 * TenantContextHeaderFilter} and ENGINEERING-STANDARDS §1.1 (tenant/authZ denial is {@code 403},
 * not {@code 401}).
 *
 * <p><strong>Tenant-optional (bootstrap) endpoints.</strong> A few authenticated endpoints CREATE
 * the tenant rather than operate within one — the org-service {@code POST /api/v1/companies}
 * bootstrap is the owner creating their FIRST company, so the token legitimately has no {@code
 * company_id} yet. A service opts such an endpoint in via {@code native.security.tenant-optional}
 * (see {@link TenantOptionalProperties}); this filter receives the compiled {@link
 * RequestMatcher}s. For a request that MATCHES one of them, a missing {@code company_id} claim is
 * NOT a {@code 403}: the filter proceeds WITHOUT binding a tenant, and the controller opens its own
 * scope (exactly as {@code CompanyController.createCompany} does for the freshly-generated company
 * id). Authentication is still required — the {@code SecurityFilterChain} already rejected a
 * missing/invalid/expired token with {@code 401}, so a tenant-optional path still needs a valid
 * token. If a tenant-optional request DOES carry a {@code company_id} claim, that tenant is still
 * bound (harmless and consistent: tenant-optional relaxes ONLY the missing-claim case, never
 * disables binding). Every non-tenant-optional {@code /api/v1/**} request keeps the strict {@code
 * 403}-on-missing-claim rule.
 *
 * <p>Probe and unauthenticated paths never reach here: {@link #shouldNotFilter} skips {@code
 * /healthz} and {@code /actuator/**} (also {@code permitAll} in the chain), and the security chain
 * has already rejected any request without a valid token, so a non-probe request that arrives here
 * always carries a {@link JwtAuthenticationToken}.
 */
public final class TenantBindingFilter extends OncePerRequestFilter implements Ordered {

  /** Stable RFC-7807 problem type for a valid-but-tenant-less token (a 403 authZ denial). */
  static final String MISSING_TENANT_TYPE = "https://errors.nativeapp.id/missing-tenant-claim";

  /** Custom claim carrying the tenant id (a Keycloak protocol mapper maps the user attribute). */
  static final String COMPANY_ID_CLAIM = "company_id";

  /**
   * Endpoints on which a missing {@code company_id} claim is tolerated (the tenant bootstrap) — the
   * compiled form of {@code native.security.tenant-optional}. Empty by default, so no endpoint is
   * tenant-optional unless a service explicitly declares it.
   */
  private final List<RequestMatcher> tenantOptionalMatchers;

  /**
   * @param tenantOptionalMatchers the tenant-optional (bootstrap) endpoint matchers; never {@code
   *     null} (pass an empty list for the strict default)
   */
  public TenantBindingFilter(List<RequestMatcher> tenantOptionalMatchers) {
    this.tenantOptionalMatchers = List.copyOf(tenantOptionalMatchers);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    Jwt jwt = currentJwt();
    if (jwt == null) {
      // Defence in depth: the security chain should already have rejected anything without a
      // validated token with 401. Never bind a tenant we cannot attribute to a verified token.
      response.sendError(HttpStatus.UNAUTHORIZED.value());
      return;
    }

    String companyId = jwt.getClaimAsString(COMPANY_ID_CLAIM);
    if (companyId == null || companyId.isBlank()) {
      if (isTenantOptional(request)) {
        // A tenant-bootstrap endpoint (e.g. POST /api/v1/companies): the token is authentic but
        // legitimately carries no tenant yet, because this request CREATES the tenant. Proceed
        // WITHOUT binding a scope — the controller opens its own (TenantContext.callAs over the
        // freshly-generated company id). Authentication was still enforced upstream (a missing
        // token would already be a 401), so this is not an open endpoint.
        chain.doFilter(request, response);
        return;
      }
      // The token is authentic (not a 401) but carries no tenant, so it is not authorized to reach
      // a tenant-scoped service: a tenant/authZ denial is 403, not 401 (ENGINEERING-STANDARDS
      // §1.1).
      writeForbiddenProblem(request, response);
      return;
    }
    String actor = jwt.getSubject();

    try {
      TenantContext.callAs(
          companyId,
          actor,
          () -> {
            chain.doFilter(request, response);
            return null;
          });
    } catch (ServletException | IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; the chain only throws the three re-thrown above, so this
      // is unreachable in practice.
      throw new ServletException(e);
    }
  }

  /**
   * Liveness/readiness probes never carry a token and are {@code permitAll} in the chain, so {@code
   * /healthz} and {@code /actuator/**} are not filtered at all — they pass through unscoped. Every
   * other path is secured by the chain, so a request that reaches the body here is authenticated.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/healthz") || path.startsWith("/actuator");
  }

  /** {@code true} if this request matches a configured tenant-optional (bootstrap) endpoint. */
  private boolean isTenantOptional(HttpServletRequest request) {
    for (RequestMatcher matcher : tenantOptionalMatchers) {
      if (matcher.matches(request)) {
        return true;
      }
    }
    return false;
  }

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken();
    }
    return null;
  }

  /**
   * Writes an RFC-7807 {@code 403} {@code application/problem+json} body for a valid token that
   * carries no {@code company_id} claim. The JSON is written directly (rather than through a
   * message converter) because this runs inside a security filter, before MVC content negotiation —
   * but it is the same {@link ProblemDetail} shape ({@code type}/{@code title}/{@code
   * status}/{@code detail}/{@code instance}) the services' {@code ApiExceptionHandler} produces, so
   * the React forms can map the stable {@code type} URI to an i18n key.
   */
  private static void writeForbiddenProblem(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\""
                + MISSING_TENANT_TYPE
                + "\",\"title\":\"Missing tenant claim\",\"status\":403,"
                + "\"detail\":\"The token is valid but carries no company_id claim.\","
                + "\"instance\":\""
                + escapeJson(request.getRequestURI())
                + "\"}");
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Run immediately after the {@code BearerTokenAuthenticationFilter} has populated the {@link
   * SecurityContextHolder}, so the validated {@link Jwt} is available, but before the request
   * reaches any controller — the tenant must be bound before the first {@code @Transactional} call.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
