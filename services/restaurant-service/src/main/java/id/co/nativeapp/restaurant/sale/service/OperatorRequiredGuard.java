package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.restaurant.config.ActorTypeProvider;
import id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException;
import id.co.nativeapp.security.OperatorPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ADR 0049 P4 enforcement guard: an {@code actor_type=device} (outlet-terminal / kiosk credential)
 * request MUST carry a verified operator session to ring a sale — the {@code OutletAccessGuard}
 * pattern applied to seller attribution instead of outlet scoping.
 *
 * <p>Shared by every SYNCHRONOUS sale-initiation choke point: {@link SaleWriter#create}, {@link
 * SaleWriter#recordInCurrentTx} (the legacy/order/park/bill CASH paths), and the single
 * PENDING-digital-mint method {@code
 * id.co.nativeapp.restaurant.payment.service.PaymentWriter#recordPendingDigitalInCurrentTx} (a
 * QRIS/CARD checkout is enforced HERE, at mint time). The async {@code
 * PaymentCaptureWriter#capture} → {@code SaleWriter#recordInCurrentTx} path runs on a Kafka
 * consumer thread with no HTTP request, so {@link ActorTypeProvider#isDevice()} always resolves
 * {@code false} there and this guard never re-fires (see {@code ActorTypeProvider} javadoc) — this
 * is intentional, not a gap: the operator was already captured (or the guard already fired) at
 * checkout time.
 *
 * <p>A normal {@code actor_type=user} login (owner/manager/cashier ringing directly, no operator
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
   * Enforces the ADR 0049 P4 policy for a device sale attempt against {@code businessId}.
   *
   * @param businessId the outlet (business unit) the sale is being rung against
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
