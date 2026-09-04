package id.co.nativeapp.restaurant.payment.domain;

/**
 * Thrown when a {@link Payment} cannot be reversed in its current state — a refund or void of a
 * payment that is not {@link Payment.Status#CAPTURED} (nor {@code PARTIALLY_REFUNDED} for a
 * refund): most commonly a sale that was ALREADY returned (a double-refund, e.g. a second terminal
 * or a lost-response retry racing the async read-model update). A domain STATE conflict, so it maps
 * to {@code 409 Conflict} ({@code payment-not-reversible}) via {@code config.PaymentAdvice} — NOT
 * the catch-all {@code 500}, which would mislabel a permanent, expected rejection as a transient
 * server fault and pollute the error log.
 *
 * <p>Extends {@link IllegalStateException} so it stays a drop-in for the raw exception the void/
 * refund guards used to throw (existing domain-test assertions on {@code IllegalStateException}
 * hold); the more-specific advice handler is what upgrades the HTTP status.
 */
public class PaymentNotReversibleException extends IllegalStateException {

  public PaymentNotReversibleException(String message) {
    super(message);
  }
}
