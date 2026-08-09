package id.co.nativeapp.barbershop.ticket.domain;

import java.util.UUID;

/**
 * Thrown when an {@code actor_type=device} (outlet-terminal / kiosk credential) request attempts to
 * ring a ticket — cash or by minting a PENDING digital tender — with NO verified {@code
 * X-Operator-Session} bound (ADR 0049 P4, the "Known gap deferred to P4" closed).
 *
 * <p>Unlike restaurant-service, barbershop does NOT thread the operator onto the metric subject
 * (commission stays attributed to the barber, {@code StaffProfile}) — this guard exists purely so a
 * device can never ring a ticket with no accountable human operator behind it. HTTP response is
 * {@code 409 Conflict} RFC-7807 ({@code https://errors.nativeapp.id/operator-required}): the
 * barber/receptionist must sign in with their PIN (minting an operator session) and retry.
 *
 * <p>A normal {@code actor_type=user} login (owner/manager/barber ringing directly, no outlet
 * device credential involved) never trips this — see {@code
 * id.co.nativeapp.barbershop.config.ActorTypeProvider}.
 */
public class OperatorRequiredException extends RuntimeException {

  /** Stable RFC-7807 problem type URI for this error — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/operator-required";

  private final UUID businessId;

  public OperatorRequiredException(UUID businessId) {
    super(
        "A device (outlet-terminal) ticket requires a verified operator session; none was"
            + " presented for business "
            + businessId);
    this.businessId = businessId;
  }

  /** The outlet (business unit) the device attempted to ring a ticket against. */
  public UUID getBusinessId() {
    return businessId;
  }
}
