package id.co.nativeapp.payment.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the acting principal's roles from the {@code X-Roles} header that the gateway stamps on
 * every authenticated request (ported verbatim from loyalty-service's {@code ActorRolesProvider};
 * see {@code TenantContextHeaderFilter} in the gateway module).
 *
 * <p>This is a thin web-tier adapter that lets the settings service layer — which is not a
 * controller and has no direct access to {@code HttpServletRequest} — consult the caller's roles
 * without threading them as parameters through the entire call chain or changing the shared {@code
 * TenantContext} record.
 *
 * <p>In the {@code dev} profile the {@link DevTenantFilter} trusts the inbound {@code
 * X-Company-Id}/{@code X-Actor} headers (it does not itself read {@code X-Roles}); in the non-dev
 * (production) profile the gateway has already validated the JWT and stripped any client-supplied
 * copy before injecting its own. Either way the header is trusted at this point.
 *
 * <p>Outside of a request context (e.g. in tests that call the service layer directly without a
 * request) {@link #currentRoles} returns an empty collection — meaning no roles are present and the
 * role guards treat the caller per the fleet's dev-recipe trust (guards reject only a caller whose
 * PRESENT roles are insufficient).
 */
@Component
public class ActorRolesProvider {

  /** The header name set by the gateway's {@code TenantContextHeaderFilter}. */
  public static final String ROLES_HEADER = "X-Roles";

  /**
   * Returns the roles granted to the current HTTP request's actor as a (possibly empty) collection
   * of role name strings (e.g. {@code "owner"}, {@code "manager"}, {@code "cashier"}).
   *
   * <p>Outside a request context (service-layer unit tests) returns an empty collection — safe
   * default.
   */
  public Collection<String> currentRoles() {
    try {
      var attrs = RequestContextHolder.currentRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        String header = sra.getRequest().getHeader(ROLES_HEADER);
        if (header != null && !header.isBlank()) {
          return Arrays.asList(header.split(","));
        }
      }
    } catch (IllegalStateException noRequest) {
      // Called outside a request context — safe default: no roles.
    }
    return Collections.emptyList();
  }

  /** {@code true} if the current actor has the {@code owner} role. */
  public boolean isOwner() {
    return currentRoles().stream().anyMatch(r -> "owner".equals(r.strip()));
  }
}
