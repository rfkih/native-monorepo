package id.co.nativeapp.restaurant.config;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the {@link SelfOrderPrincipal} {@link SelfOrderTokenFilter} bound to the current request —
 * the thin web-tier adapter that lets {@code selforder.service.SelfOrderService} (not a controller,
 * no direct {@code HttpServletRequest} access) learn which outlet/table an anonymous, ALREADY-
 * VERIFIED self-order caller is scoped to, exactly the {@link ActorRolesProvider} pattern.
 *
 * <p>Outside a request context that passed through {@link SelfOrderTokenFilter} (Kafka consumer
 * threads, a service-layer test that binds {@code TenantContext} directly) {@link #current()}
 * returns empty — the caller must fail closed rather than silently operate with no principal.
 */
@Component
public class SelfOrderPrincipalProvider {

  /** The bound {@link SelfOrderPrincipal}, if this request passed through the token filter. */
  public Optional<SelfOrderPrincipal> current() {
    try {
      var attrs = RequestContextHolder.currentRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        Object value = sra.getRequest().getAttribute(SelfOrderPrincipal.REQUEST_ATTRIBUTE);
        if (value instanceof SelfOrderPrincipal principal) {
          return Optional.of(principal);
        }
      }
    } catch (IllegalStateException noRequest) {
      // Called outside a request context — safe default: no principal bound.
    }
    return Optional.empty();
  }

  /**
   * The bound {@link SelfOrderPrincipal}, or throws — every self-order controller method runs
   * behind {@link SelfOrderTokenFilter}, which either binds a principal or rejects the request
   * before the controller is ever reached, so a missing principal here is a wiring bug, not a
   * client-facing fault.
   */
  public SelfOrderPrincipal require() {
    return current()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No SelfOrderPrincipal bound; SelfOrderTokenFilter must run before this call"));
  }
}
