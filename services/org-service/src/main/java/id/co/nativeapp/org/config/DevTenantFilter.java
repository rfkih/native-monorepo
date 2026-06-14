package id.co.nativeapp.org.config;

import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request-edge tenant binding — a minimal DEV stand-in for the JWT/gateway that arrives in M1.1.
 *
 * <p>It reads the tenant ({@code company_id}) and actor from the {@code X-Company-Id} and {@code
 * X-Actor} request headers and wraps the downstream work in {@link TenantContext#callAs(String,
 * String, java.util.concurrent.Callable)}, exactly where the gateway will later bind the validated
 * JWT's {@code company_id} claim and {@code sub}. It performs NO authentication and trusts the
 * headers — it is explicitly a placeholder and must be replaced by real JWT validation before this
 * service is exposed.
 *
 * <p><strong>Production safety.</strong> Because it trusts unauthenticated headers, this filter
 * must NEVER be active in production. The {@code @Profile("dev")} and
 * {@code @ConditionalOnProperty} annotations combine with logical AND, so it registers ONLY when
 * BOTH the {@code dev} profile is active AND {@code native.dev-tenant-filter.enabled=true} is set
 * explicitly (the safer behavior: either guard alone is insufficient). A production profile fails
 * the profile guard regardless of the property, so it has no such bean.
 *
 * <p><strong>The create-company bootstrap exemption (M1.2).</strong> Creating a company CREATES a
 * new tenant, so there is no existing {@code app.current_tenant} to bind at the edge — the
 * company's own id <em>is</em> the new tenant, generated inside the create-company flow. {@code
 * POST /api/v1/companies} is therefore exempt from the tenant requirement (like {@code /healthz}):
 * it passes through WITHOUT a bound tenant scope, and the {@code CompanyController} opens the
 * tenant scope itself ({@code TenantContext.callAs(newCompanyId, actor, ...)}) so the auto-RLS
 * aspect sets the GUC to the new id and the RLS {@code WITH CHECK} passes on the bootstrap insert.
 * The actor for that flow still comes from the {@code X-Actor} header (read by the controller); in
 * production it is the authenticated owner/account creating their company.
 *
 * <p><strong>Tenant id is a UUID.</strong> In production the {@code company_id} is the JWT {@code
 * company_id} claim, which is a UUID. {@code Auditable} stores it as text and the {@code
 * company.company_id} column is {@code VARCHAR(64)}, but the {@code outbox} {@code company_id}
 * column is {@code UUID}; a non-UUID tenant id would persist a row and then blow up on the outbox
 * write ({@code UUID.fromString}) — a {@code 500} after a partial effect. So this filter validates
 * the tenant id is a UUID at the EDGE for the tenant-bound endpoints: a missing, blank, or non-UUID
 * {@code X-Company-Id} (or a missing/blank actor) is rejected with {@code 400} and NO scope is
 * bound. Probe and bootstrap endpoints are exempt (see below).
 *
 * <p><strong>Probe + bootstrap exemption.</strong> {@code /healthz}, {@code /actuator/**}, and the
 * create-company bootstrap {@code POST /api/v1/companies} are skipped entirely and pass through
 * unscoped. Any other data-touching handler still requires a tenant via {@link
 * TenantContext#require()}, and RLS fails closed for any DB access.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "native.dev-tenant-filter.enabled", havingValue = "true")
public class DevTenantFilter extends OncePerRequestFilter implements Ordered {

  /** Header carrying the tenant id (stands in for the JWT {@code company_id} claim). */
  public static final String COMPANY_HEADER = "X-Company-Id";

  /** Header carrying the acting principal (stands in for the JWT {@code sub}). */
  public static final String ACTOR_HEADER = "X-Actor";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String companyId = request.getHeader(COMPANY_HEADER);
    String actor = request.getHeader(ACTOR_HEADER);

    if (companyId == null || companyId.isBlank() || actor == null || actor.isBlank()) {
      // The probe + bootstrap endpoints are exempt (shouldNotFilter); a non-exempt
      // request that needs a tenant must carry both headers, so reject the
      // missing/blank case at the edge rather than letting an unscoped data request
      // fail deeper as a 500.
      response.sendError(
          HttpServletResponse.SC_BAD_REQUEST,
          "Missing or blank " + COMPANY_HEADER + " / " + ACTOR_HEADER);
      return;
    }

    if (!isUuid(companyId)) {
      // The tenant id MUST be a UUID (the production company_id claim is a UUID and
      // the outbox.company_id column is UUID). Reject a non-UUID here, before binding
      // any scope, so it can never persist a row and then fail on the outbox write.
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, COMPANY_HEADER + " must be a UUID");
      return;
    }

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
      // callAs declares checked Exception; the chain only throws the three
      // re-thrown above, so this is unreachable in practice.
      throw new ServletException(e);
    }
  }

  /**
   * Liveness/readiness probes never carry a tenant header, and the create-company bootstrap creates
   * its own tenant, so {@code /healthz}, {@code /actuator/**}, and {@code POST /api/v1/companies}
   * are not filtered at all — they pass through unscoped.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path.equals("/healthz") || path.startsWith("/actuator")) {
      return true;
    }
    // The create-company bootstrap: POST /api/v1/companies (exactly — not its
    // sub-resources such as /api/v1/companies/{id}/businesses, which ARE tenant-bound).
    return "POST".equalsIgnoreCase(request.getMethod()) && path.equals("/api/v1/companies");
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Bind the tenant as early as possible, before any handler runs. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
