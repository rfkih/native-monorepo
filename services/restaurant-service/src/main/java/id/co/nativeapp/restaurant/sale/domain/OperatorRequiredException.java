package id.co.nativeapp.restaurant.sale.domain;

import java.util.UUID;

/**
 * Thrown when an {@code actor_type=device} (outlet-terminal / kiosk credential) request attempts to
 * ring a sale — synchronously (checkout) or by minting a PENDING digital tender — with NO verified
 * {@code X-Operator-Session} bound (ADR 0049 P4, the "Known gap deferred to P4" closed).
 *
 * <p>A device sale with no operator would attribute the seller to the device itself (or nothing at
 * all), silently breaking commission — this rejects the write outright instead. HTTP response is
 * {@code 409 Conflict} RFC-7807 ({@code https://errors.nativeapp.id/operator-required}): the
 * cashier must sign in with their PIN (minting an operator session) and retry.
 *
 * <p>A normal {@code actor_type=user} login (owner/manager/cashier ringing directly, no outlet
 * device credential involved) never trips this — see {@code
 * id.co.nativeapp.restaurant.config.ActorTypeProvider}.
 */
public class OperatorRequiredException extends RuntimeException {

  /** Stable RFC-7807 problem type URI for this error — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/operator-required";

  private final UUID businessId;

  public OperatorRequiredException(UUID businessId) {
    super(
        "A device (outlet-terminal) sale requires a verified operator session; none was presented"
            + " for business "
            + businessId);
    this.businessId = businessId;
  }

  /** The outlet (business unit) the device attempted to ring a sale against. */
  public UUID getBusinessId() {
    return businessId;
  }
}
