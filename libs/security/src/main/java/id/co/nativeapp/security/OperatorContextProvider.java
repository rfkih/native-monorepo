package id.co.nativeapp.security;

import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the {@link OperatorPrincipal} {@link OperatorSessionFilter} bound to the current request
 * (ADR 0049 P2) — the thin web-tier adapter that lets a vertical's service layer (e.g.
 * restaurant-service's {@code SaleWriter}, not a controller, no direct {@code HttpServletRequest}
 * access) learn which operator (if any) is ringing the current sale, exactly the restaurant-service
 * {@code SelfOrderPrincipalProvider}/{@code ActorRolesProvider} pattern — lifted into {@code
 * libs/security} here since every vertical needs the identical adapter (P2 wires restaurant only;
 * carwash/barbershop opt in later, P4).
 *
 * <p>A stateless reader with no side effects, registered as a {@code @Bean} by {@link
 * OperatorContextAutoConfiguration} — NOT a plain {@code @Component}: {@code libs/security} lives
 * outside every consuming service's {@code id.co.nativeapp.<service>} component-scan root, so a
 * bare {@code @Component} here would never be picked up. The auto-configuration is what makes this
 * bean reach every service's context — harmless even in a service that never registers {@link
 * OperatorSessionFilter}: {@link #current()} then simply always returns empty (no request attribute
 * is ever set).
 *
 * <p>Outside a request context that passed through {@link OperatorSessionFilter} (Kafka consumer
 * threads, a service-layer test that binds {@code TenantContext} directly without a request) {@link
 * #current()} returns empty — the caller falls back to today's actor-based behaviour rather than
 * failing closed, matching the filter's own "absent header is the norm" posture.
 */
public class OperatorContextProvider {

  /** The bound {@link OperatorPrincipal}, if this request passed verification and carried one. */
  public Optional<OperatorPrincipal> current() {
    try {
      var attrs = RequestContextHolder.currentRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        Object value = sra.getRequest().getAttribute(OperatorPrincipal.REQUEST_ATTRIBUTE);
        if (value instanceof OperatorPrincipal principal) {
          return Optional.of(principal);
        }
      }
    } catch (IllegalStateException noRequest) {
      // Called outside a request context — safe default: no principal bound.
    }
    return Optional.empty();
  }
}
