package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.config.ActorTypeProvider;
import id.co.nativeapp.barbershop.ticket.domain.OperatorRequiredException;
import id.co.nativeapp.security.OperatorPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ADR 0049 P4 enforcement guard: an {@code actor_type=device} (outlet-terminal / kiosk credential)
 * request MUST carry a verified operator session to ring a ticket — the {@code OutletAccessGuard}
 * pattern applied to seller attribution instead of outlet scoping.
 *
 * <p>Enforce-only for barbershop: unlike restaurant-service, the ticket's commission-metric subject
 * stays the barber ({@code StaffProfile}) — this guard does NOT thread the operator anywhere, it
 * only rejects a device ticket with no accountable operator.
 *
 * <p>Called once, at the single synchronous choke point {@link TicketWriter#create} (which mints
 * BOTH the CASH-recognized-revenue path AND the PENDING-digital-mint path in the same method — see
 * its class javadoc). The async {@link TicketCaptureWriter#capture} runs on a Kafka consumer thread
 * with no HTTP request, so {@link ActorTypeProvider#isDevice()} always resolves {@code false} there
 * and the guard is never re-enforced.
 *
 * <p>A normal {@code actor_type=user} login (owner/manager/barber ringing directly, no operator
 * session) is unaffected — {@link ActorTypeProvider#isDevice()} is {@code false}, so this guard is
 * inert regardless of whether an operator is present.
 */
@Component
public class OperatorRequiredGuard {

  private final ActorTypeProvider actorTypeProvider;

  public OperatorRequiredGuard(ActorTypeProvider actorTypeProvider) {
    this.actorTypeProvider = actorTypeProvider;
  }

  /**
   * Enforces the ADR 0049 P4 policy for a device ticket attempt against {@code businessId}.
   *
   * @param businessId the outlet (business unit) the ticket is being rung against
   * @param operator the already-resolved verified operator principal for this request (or empty)
   * @throws OperatorRequiredException if the current request is a device (outlet-terminal) actor
   *     with no verified operator session bound
   */
  public void enforce(UUID businessId, Optional<OperatorPrincipal> operator) {
    if (actorTypeProvider.isDevice() && operator.isEmpty()) {
      throw new OperatorRequiredException(businessId);
    }
  }
}
