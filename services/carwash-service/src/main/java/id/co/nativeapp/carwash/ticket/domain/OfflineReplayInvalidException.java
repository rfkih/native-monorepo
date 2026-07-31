package id.co.nativeapp.carwash.ticket.domain;

/**
 * Thrown by {@code id.co.nativeapp.carwash.ticket.service.OfflineReplayGuard} when an
 * offline-replay checkout (Phase 5 offline mode, ADR 0028) request is malformed:
 *
 * <ul>
 *   <li>a {@code clientOccurredAt} supplied without {@code offlineReplay = true};
 *   <li>a {@code clientOccurredAt} outside the accepted {@code [now - 48h, now + 5m]} window;
 *   <li>an {@code offlineReplay = true} request carrying a non-CASH tender, a {@code couponCode}, a
 *       loyalty-points redemption, or a gift-card redemption — an offline till has no fresh view of
 *       that server-side state (a {@code loyaltyMemberId} alone, for earn attribution only, remains
 *       allowed).
 * </ul>
 *
 * <p>Maps to {@code 422 Unprocessable Entity} via {@code config.TicketAdvice}, mirroring {@link
 * MixedCurrencyException}'s style.
 */
public class OfflineReplayInvalidException extends RuntimeException {

  public OfflineReplayInvalidException(String message) {
    super(message);
  }
}
