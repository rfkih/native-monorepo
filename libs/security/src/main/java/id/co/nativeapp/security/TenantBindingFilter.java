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
 * <p>It reads the login's ALLOWED companies ({@code company_id} claim — {@code string | string[]},
 * multi-company ownership ADR 0021) and actor ({@code sub}) from the token, resolves the ACTIVE
 * company (the inbound {@code X-Company-Id} selection when — and only when — it is in the token's
 * allowed set; the first allowed company otherwise), and wraps the rest of the chain in {@link
 * TenantContext#callAs(String, String, java.util.concurrent.Callable)}, exactly where the dev
 * {@code DevTenantFilter} binds from trusted headers. The auto-RLS aspect then sets {@code
 * app.current_tenant} on every {@code @Transactional} unit of work, so row-level security keys on
 * the verified tenant.
 *
 * <p><strong>An inbound {@code X-Company-Id} is honoured only as a TOKEN-VALIDATED selection,
 * never trusted verbatim.</strong> A forged {@code X-Company-Id} sent straight to a service port
 * naming a company outside the token's set is rejected {@code 403} ({@code
 * invalid-company-selection}) — it can never bind an arbitrary tenant. This keeps the gap closed
 * that the gateway alone could not (defense in depth), while letting a multi-company login switch
 * its active company per request.
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

  /**
   * Stable RFC-7807 problem type for an {@code X-Company-Id} selection outside the token's allowed
   * set (a 403 authZ denial — an authenticated caller asking for a tenant it does not belong to).
   */
  static final String INVALID_SELECTION_TYPE =
      "https://errors.nativeapp.id/invalid-company-selection";

  /**
   * Custom claim carrying the login's allowed company ids (a Keycloak protocol mapper maps the
   * multi-valued user attribute; {@code string | string[]} — scalar for pre-rollout tokens).
   */
  static final String COMPANY_ID_CLAIM = "company_id";

  /** The client's active-company selection header (validated against the claim set, never trusted). */
  static final String COMPANY_HEADER = "X-Company-Id";

  /**
   * Endpoints on which a missing {@code company_id} claim is tolerated (the tenant bootstrap) — the
   * compiled form of {@code native.security.tenant-optional}. Empty by default, so no endpoint is
   * tenant-optional unless a service explicitly declares it.
   */
  private final List<RequestMatcher> tenantOptionalMatchers;

  /**
   * Fully public (unauthenticated) endpoints — the compiled form of {@code
   * native.security.public-paths}. These carry no JWT at all, so {@link #shouldNotFilter} must
   * return {@code true} for them; otherwise the filter body would see a {@code null} JWT and emit
   * {@code 401} despite the path being {@code permitAll} in the security chain (filters run
   * regardless of {@code permitAll}). Empty by default.
   */
  private final List<RequestMatcher> publicPathMatchers;

  /**
   * @param tenantOptionalMatchers the tenant-optional (bootstrap) endpoint matchers; never {@code
   *     null} (pass an empty list for the strict default)
   * @param publicPathMatchers the fully public (unauthenticated) endpoint matchers; never {@code
   *     null} (pass an empty list if there are none)
   */
  public TenantBindingFilter(
      List<RequestMatcher> tenantOptionalMatchers, List<RequestMatcher> publicPathMatchers) {
    this.tenantOptionalMatchers = List.copyOf(tenantOptionalMatchers);
    this.publicPathMatchers = List.copyOf(publicPathMatchers);
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

    List<String> allowed = extractCompanyIds(jwt);
    if (allowed.isEmpty()) {
      if (isTenantOptional(request)) {
        // A tenant-bootstrap endpoint (e.g. POST /api/v1/companies, GET /api/v1/companies/mine):
        // the token is authentic but legitimately carries no tenant yet, because this request
        // CREATES the tenant (or lists an empty membership set). Proceed WITHOUT binding a scope —
        // the controller opens its own (TenantContext.callAs over the freshly-generated company
        // id). Authentication was still enforced upstream (a missing token would already be a
        // 401), so this is not an open endpoint.
        chain.doFilter(request, response);
        return;
      }
      // The token is authentic (not a 401) but carries no tenant, so it is not authorized to reach
      // a tenant-scoped service: a tenant/authZ denial is 403, not 401 (ENGINEERING-STANDARDS
      // §1.1).
      writeForbiddenProblem(request, response);
      return;
    }

    // Resolve the ACTIVE company: the client's X-Company-Id selection if (and only if) the token
    // allows it, else the token's first company. A selection outside the allowed set is rejected —
    // never silently replaced — so a forged header can never bind a foreign tenant (ADR 0021).
    String requested = request.getHeader(COMPANY_HEADER);
    String companyId;
    if (requested == null || requested.isBlank()) {
      companyId = allowed.getFirst();
    } else if (allowed.contains(requested)) {
      companyId = requested;
    } else {
      writeInvalidSelectionProblem(request, response);
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
   * Liveness/readiness probes and fully public endpoints never carry a token, so they must be
   * skipped entirely rather than entering the filter body (which expects a validated JWT and would
   * emit {@code 401} on a {@code null} authentication). Skipped paths:
   *
   * <ul>
   *   <li>{@code /healthz} and {@code /actuator/**} — probe/scrape endpoints, always {@code
   *       permitAll} in the chain;
   *   <li>any path in {@code publicPathMatchers} — the compiled form of {@code
   *       native.security.public-paths} (e.g. {@code POST /api/v1/signup}).
   * </ul>
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path.equals("/healthz") || path.startsWith("/actuator")) {
      return true;
    }
    for (RequestMatcher matcher : publicPathMatchers) {
      if (matcher.matches(request)) {
        return true;
      }
    }
    return false;
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
   * Normalizes the {@code company_id} claim to the login's allowed-company list: a multi-valued
   * mapper emits a JSON array (first = default active company); pre-rollout tokens carry a scalar.
   * Blank values dropped; deduplicated, order-preserving; empty when the login has no company yet.
   * Public so services can read the caller's membership set from the same verified token (e.g. the
   * org-service {@code GET /api/v1/companies/mine}).
   */
  public static List<String> extractCompanyIds(Jwt jwt) {
    Object claim = jwt.getClaim(COMPANY_ID_CLAIM);
    List<String> ids = new java.util.ArrayList<>();
    if (claim instanceof String s) {
      if (!s.isBlank()) {
        ids.add(s);
      }
    } else if (claim instanceof java.util.Collection<?> values) {
      for (Object value : values) {
        if (value == null) {
          continue;
        }
        String id = value.toString();
        if (!id.isBlank() && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    return ids;
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

  /**
   * Writes an RFC-7807 {@code 403} for an {@code X-Company-Id} selection outside the token's
   * allowed set — the caller is authenticated but asked for a tenant it does not belong to.
   */
  private static void writeInvalidSelectionProblem(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\""
                + INVALID_SELECTION_TYPE
                + "\",\"title\":\"Invalid company selection\",\"status\":403,"
                + "\"detail\":\"The selected company is not in the token's allowed set.\","
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
