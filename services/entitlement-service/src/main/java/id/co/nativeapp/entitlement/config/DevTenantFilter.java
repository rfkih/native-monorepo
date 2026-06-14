package id.co.nativeapp.entitlement.config;

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
 * Request-edge tenant binding for the entitlement API — a minimal DEV stand-in for the JWT/gateway.
 * It reads the tenant ({@code company_id}) and actor from the {@code X-Company-Id} and {@code
 * X-Actor} request headers and wraps the downstream work in {@link TenantContext#callAs(String,
 * String, java.util.concurrent.Callable)}, exactly where the gateway will later bind the validated
 * JWT's {@code company_id} claim and {@code sub}. It performs NO authentication and trusts the
 * headers — a placeholder that must be replaced by real JWT validation before this service is
 * exposed.
 *
 * <p><strong>Consumer path is unaffected.</strong> The {@code CompanyCreated}
 * {@code @KafkaListener} binds its own tenant scope from the event's {@code company_id} (not from
 * an HTTP header); this filter only governs the inbound {@code /api/v1/entitlements} request paths.
 *
 * <p><strong>Production safety.</strong> Because it trusts unauthenticated headers, this filter
 * must NEVER be active in production. It is gated to register only under the {@code dev} profile
 * AND when {@code native.dev-tenant-filter.enabled=true} is set explicitly, so a production profile
 * that does neither has no such bean (the non-dev {@code libs/security} JWT chain binds the tenant
 * instead).
 *
 * <p><strong>Probe exemption.</strong> {@code /healthz} and {@code /actuator/**} must never depend
 * on a tenant header, so they are skipped entirely and pass through unscoped.
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
      response.sendError(
          HttpServletResponse.SC_BAD_REQUEST,
          "Missing or blank " + COMPANY_HEADER + " / " + ACTOR_HEADER);
      return;
    }

    if (!isUuid(companyId)) {
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
      // callAs declares checked Exception; the chain only throws the three re-thrown above.
      throw new ServletException(e);
    }
  }

  /**
   * Liveness/readiness probes never carry a tenant header, so {@code /healthz} and {@code
   * /actuator/**} are not filtered at all — they pass through unscoped.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/healthz") || path.startsWith("/actuator");
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
