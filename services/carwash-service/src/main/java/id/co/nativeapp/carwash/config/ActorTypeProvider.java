package id.co.nativeapp.carwash.config;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the acting principal's actor type ({@code device | user}, ADR 0049) from the {@code
 * X-Actor-Type} header the gateway stamps on every authenticated request (see {@code
 * TenantContextHeaderFilter}) — the {@link ActorRolesProvider} pattern, applied to the ADR 0049 P4
 * device-guard need.
 *
 * <p>This is a thin web-tier adapter that lets a non-controller service bean (e.g. {@code
 * ticket.service.TicketWriter}) consult the caller's actor type without threading it as a parameter
 * through the entire call chain or changing the shared {@code TenantContext} record.
 *
 * <p>In the {@code dev} profile the request-edge tenant filter trusts the inbound {@code
 * X-Actor-Type} header exactly like {@code X-Roles}; in the non-dev (production) profile the
 * gateway has already validated the JWT and stripped any client-supplied copy before injecting its
 * own {@code actor_type} claim (a client can therefore never self-declare {@code device} or {@code
 * user}).
 *
 * <p><strong>CRITICAL default — {@code "user"}.</strong> {@link #currentActorType()} returns {@code
 * "user"} both when the header is absent/blank AND when there is NO request context at all (a Kafka
 * consumer thread, or a service-layer test that calls straight through {@link
 * id.co.nativeapp.tenant.TenantContext#callAs}). This is exactly what keeps the ADR 0049 P4 device
 * guard inert on any async event-consumer path and inert for a normal {@code actor_type=user} login
 * that carries no operator session (today's exact pre-P4 behaviour).
 */
@Component
public class ActorTypeProvider {

  /** The header name set by the gateway's {@code TenantContextHeaderFilter}. */
  public static final String ACTOR_TYPE_HEADER = "X-Actor-Type";

  /** The device (outlet-terminal / kiosk credential) actor type value (ADR 0049). */
  public static final String DEVICE = "device";

  /** The default / ordinary person-login actor type value. */
  public static final String USER = "user";

  /**
   * Returns the current request's actor type — {@code "device"} or {@code "user"} — defaulting to
   * {@code "user"} when the header is absent/blank or there is no request context at all (see class
   * javadoc for why that default is load-bearing).
   */
  public String currentActorType() {
    try {
      var attrs = RequestContextHolder.currentRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        String header = sra.getRequest().getHeader(ACTOR_TYPE_HEADER);
        if (header != null && !header.isBlank()) {
          return header.strip();
        }
      }
    } catch (IllegalStateException noRequest) {
      // Called outside a request context (Kafka consumer thread, a direct service-layer call) —
      // safe default: "user" (see class javadoc — this is what keeps an async path inert).
    }
    return USER;
  }

  /** {@code true} if the current request's actor type is {@code "device"} (ADR 0049 P4 guard). */
  public boolean isDevice() {
    return DEVICE.equals(currentActorType());
  }
}
