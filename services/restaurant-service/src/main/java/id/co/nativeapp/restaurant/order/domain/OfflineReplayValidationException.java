package id.co.nativeapp.restaurant.order.domain;

/**
 * Thrown by {@code OfflineReplayGuard} when a checkout request violates the Phase 5 (ADR 0028)
 * offline-replay contract:
 *
 * <ul>
 *   <li>{@code clientOccurredAt} supplied without {@code offlineReplay=true};
 *   <li>{@code offlineReplay=true} with a {@code clientOccurredAt} more than 48h in the past, or
 *       more than 5min in the future;
 *   <li>{@code offlineReplay=true} with a forbidden field: a non-{@code CASH} tender, a missing
 *       payment, a {@code couponCode}, a positive {@code loyaltyRedeemPoints}, or a gift-card
 *       redemption — offline is CASH-only, quick-sale-only (ADR 0028).
 * </ul>
 *
 * <p>Maps to {@code 422 Unprocessable Entity} via {@code config.OrderAdvice}.
 */
public class OfflineReplayValidationException extends RuntimeException {

  public OfflineReplayValidationException(String message) {
    super(message);
  }
}
