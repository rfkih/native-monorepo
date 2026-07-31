package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.OfflineReplayInvalidException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Validates + resolves the checkout's {@code occurredAt} for an offline-replay checkout (Phase 5
 * offline mode, ADR 0028) — a cash-only ticket queued client-side while offline and REPLAYED
 * through this SAME checkout endpoint once connectivity returns. Exactly-once delivery is already
 * handled by the existing {@code (company_id, idempotency_key)} uniqueness (a replay is just a
 * retry); this guard only validates the client-asserted occurrence time and forbids the request
 * shapes an offline till cannot have safely evaluated (promotions/loyalty/gift-card state it never
 * saw fresh).
 *
 * <p><strong>Resolution (pinned contract):</strong>
 *
 * <ul>
 *   <li>{@code clientOccurredAt} present but {@code offlineReplay != TRUE} → rejected ({@code 422}
 *       — a non-offline checkout never backdates).
 *   <li>{@code offlineReplay = TRUE} with a {@code clientOccurredAt} more than 48h in the past, or
 *       more than 5 minutes in the future, is rejected ({@code 422}); a {@code null} value falls
 *       back to {@code now}.
 *   <li>{@code offlineReplay = TRUE}: the tender must be CASH, and {@code couponCode}, {@code
 *       loyaltyRedeemPoints}, {@code giftCardId}, {@code giftCardRedeemMinor} must all be
 *       absent/zero — {@code loyaltyMemberId} alone (earn attribution, no redemption) IS allowed.
 * </ul>
 *
 * <p>Not itself {@code @Transactional} — a stateless request-shape validator, called from inside
 * {@link TicketWriter#create}'s own {@code REQUIRES_NEW} transaction, exactly where {@code
 * occurredAt} was previously minted as a bare {@code Instant.now()}.
 */
@Component
public class OfflineReplayGuard {

  /** The furthest a client-asserted occurrence may fall in the past. */
  private static final Duration MAX_PAST = Duration.ofHours(48);

  /** The furthest a client-asserted occurrence may fall in the future (clock-skew allowance). */
  private static final Duration MAX_FUTURE = Duration.ofMinutes(5);

  /**
   * Validates {@code request}'s offline-replay fields against {@code now} and returns the ticket's
   * {@code occurredAt}: the validated {@code clientOccurredAt} for a valid offline replay, or
   * {@code now} for every other case (normal online checkout, or an offline replay with no
   * {@code clientOccurredAt}).
   *
   * @throws OfflineReplayInvalidException per the class javadoc's resolution rules (→ 422)
   */
  public Instant resolveOccurredAt(CheckoutRequest request, Instant now) {
    boolean offlineReplay = Boolean.TRUE.equals(request.offlineReplay());
    Instant clientOccurredAt = request.clientOccurredAt();

    if (clientOccurredAt != null && !offlineReplay) {
      throw new OfflineReplayInvalidException(
          "clientOccurredAt is only accepted when offlineReplay is true");
    }
    if (!offlineReplay) {
      return now;
    }

    if (clientOccurredAt != null) {
      // The 48h window is ADDITIONALLY clamped to the start of the current UTC month: the GL/tax
      // period is month-grained and the prior month may already be FILED (the VAT return seals it,
      // ADR 0017) — a replay must never be able to post into a sealed period (security review of
      // ADR 0028). A cross-month replay is rejected and stays visible in the device's sync center
      // for manual entry, mirroring the amortization "no backdating into sealed run months" guard.
      Instant startOfCurrentMonthUtc =
          java.time.YearMonth.from(now.atZone(java.time.ZoneOffset.UTC))
              .atDay(1)
              .atStartOfDay(java.time.ZoneOffset.UTC)
              .toInstant();
      Instant earliestAllowed = max(now.minus(MAX_PAST), startOfCurrentMonthUtc);
      Instant latestAllowed = now.plus(MAX_FUTURE);
      if (clientOccurredAt.isBefore(earliestAllowed)) {
        throw new OfflineReplayInvalidException(
            "clientOccurredAt "
                + clientOccurredAt
                + " is outside the allowed window (earliest allowed "
                + earliestAllowed
                + " — at most 48h back and never before the current month's GL period)");
      }
      if (clientOccurredAt.isAfter(latestAllowed)) {
        throw new OfflineReplayInvalidException(
            "clientOccurredAt "
                + clientOccurredAt
                + " is more than 5 minutes in the future (latest allowed "
                + latestAllowed
                + ")");
      }
    }

    requireCashOnlyNoPromotionsOrRedemptions(request);

    return clientOccurredAt != null ? clientOccurredAt : now;
  }

  /**
   * An offline till has no fresh view of coupon/points/gift-card server-side state — an
   * offline-replay checkout is restricted to a plain CASH sale. {@code loyaltyMemberId} alone is
   * exempt (earn attribution only, no redemption to clamp against stale data).
   */
  private void requireCashOnlyNoPromotionsOrRedemptions(CheckoutRequest request) {
    if (request.payment() == null || request.payment().tenderType() != TenderType.CASH) {
      throw new OfflineReplayInvalidException("an offline-replay checkout must be tendered CASH");
    }
    if (request.couponCode() != null && !request.couponCode().isBlank()) {
      throw new OfflineReplayInvalidException(
          "an offline-replay checkout may not carry a couponCode");
    }
    if (request.loyaltyRedeemPoints() != null && request.loyaltyRedeemPoints() > 0L) {
      throw new OfflineReplayInvalidException(
          "an offline-replay checkout may not redeem loyalty points");
    }
    if (request.giftCardId() != null
        || (request.giftCardRedeemMinor() != null && request.giftCardRedeemMinor() > 0L)) {
      throw new OfflineReplayInvalidException(
          "an offline-replay checkout may not redeem a gift card");
    }
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
