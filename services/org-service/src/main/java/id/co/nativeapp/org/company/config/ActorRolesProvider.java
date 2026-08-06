package id.co.nativeapp.org.company.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the acting principal's roles from the {@code X-Roles} header the gateway stamps on every
 * authenticated request (see the gateway's {@code TenantContextHeaderFilter}).
 *
 * <p>This is the org-service copy of the fleet-wide precedent for a service-layer role guard finer
 * than the gateway's route-level gate — restaurant-service / carwash-service / barbershop-service /
 * loyalty-service each carry their own identical {@code ActorRolesProvider} (a thin, per-service
 * web adapter, not a shared lib, so each service reads its own request headers without threading
 * roles as parameters through the call chain or overloading the shared {@code TenantContext}
 * record). It lives under {@code company.config} (not the service-wide {@code org.config}) because
 * the plan-tier owner-only guard (ADR 0044) is currently its only caller.
 *
 * <p>In production the gateway strips any client-supplied copy of the header before injecting its
 * own (derived from the verified JWT), so the header is trusted at the point this class reads it.
 * Outside a request context (Kafka consumer threads, service-layer unit tests that call {@link
 * id.co.nativeapp.org.company.service.PlanTierWriter} directly) {@link #currentRoles()} returns an
 * empty collection — a safe default the caller interprets per its own empty-roles-pass policy.
 */
@Component
public class ActorRolesProvider {

  /** The header name set by the gateway's {@code TenantContextHeaderFilter}. */
  public static final String ROLES_HEADER = "X-Roles";

  /**
   * Returns the roles granted to the current HTTP request's actor as a (possibly empty) collection
   * of role name strings (e.g. {@code "owner"}, {@code "manager"}).
   *
   * <p>Outside a request context returns an empty collection — safe default.
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
