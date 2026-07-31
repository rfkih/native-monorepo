package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.restaurant.order.domain.OfflineReplayValidationException;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Enforces the Phase 5 (ADR 0028) offline-replay contract on {@code POST /api/v1/orders} and
 * resolves the order's effective {@code occurredAt}.
 *
 * <p>Offline replay is just a retry of the existing checkout — the client queues a cash sale in
 * IndexedDB while disconnected and replays it, unchanged, once back online. Two request fields
 * carry the contract: {@code offlineReplay} and {@code clientOccurredAt}. Because the retry is
 * indistinguishable from a normal checkout apart from these two fields, this guard is the single
 * choke point that keeps the offline path CASH-only, quick-sale-only, and bounded in how far it can
 * back-date the GL period.
 *
 * <p>Shared by {@code OrderWriter#checkout} only — {@code park}/{@code payParked} are explicitly
 * out of scope for offline replay (ADR 0028: "No open bills, parking, voids, refunds, coupons,
 * points redemption, or gift cards offline").
 */
@Component
public class OfflineReplayGuard {

  /** {@code clientOccurredAt} may not be more than this far in the past. */
  private static final Duration MAX_PAST = Duration.ofHours(48);

  /** {@code clientOccurredAt} may not be more than this far in the future (device clock skew). */
  private static final Duration MAX_FUTURE = Duration.ofMinutes(5);

  /**
   * Validates the request against the offline-replay contract and returns the order's effective
   * {@code occurredAt} — {@code request.clientOccurredAt()} when it was supplied and accepted, or
   * {@code now} otherwise (the normal online path, and the {@code offlineReplay=true} / {@code
   * clientOccurredAt=null} path both fall back to {@code now}).
   *
   * @param request the checkout request
   * @param now the server's current instant (injected so tests can pin it)
   * @return the instant to use as the order/sale's {@code occurredAt}
   * @throws OfflineReplayValidationException if the contract is violated (surfaces as {@code 422})
   */
  public Instant resolveOccurredAt(CheckoutRequest request, Instant now) {
    boolean offlineReplay = Boolean.TRUE.equals(request.offlineReplay());
    Instant clientOccurredAt = request.clientOccurredAt();

    if (clientOccurredAt != null && !offlineReplay) {
      throw new OfflineReplayValidationException(
          "clientOccurredAt may only be supplied when offlineReplay=true");
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
        throw new OfflineReplayValidationException(
            "clientOccurredAt "
                + clientOccurredAt
                + " is outside the allowed window (earliest allowed "
                + earliestAllowed
                + " — at most 48h back and never before the current month's GL period)");
      }
      if (clientOccurredAt.isAfter(latestAllowed)) {
        throw new OfflineReplayValidationException(
            "clientOccurredAt "
                + clientOccurredAt
                + " is more than 5 minutes in the future (latest allowed "
                + latestAllowed
                + ")");
      }
    }

    enforceOfflineFieldMatrix(request);

    return clientOccurredAt != null ? clientOccurredAt : now;
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  /**
   * Offline replay is CASH-only, quick-sale-only (ADR 0028): a coupon, a points redemption, a
   * gift-card redemption, or any non-CASH tender consumes shared server state that cannot be
   * checked from a disconnected device. {@code loyaltyMemberId} alone (earn attribution) is allowed
   * — it rides the queued payload harmlessly.
   */
  private void enforceOfflineFieldMatrix(CheckoutRequest request) {
    if (request.payment() == null || request.payment().tenderType() != TenderType.CASH) {
      throw new OfflineReplayValidationException(
          "offlineReplay requires a payment with tenderType=CASH");
    }
    if (request.couponCode() != null && !request.couponCode().isBlank()) {
      throw new OfflineReplayValidationException("offlineReplay does not allow a couponCode");
    }
    if (request.loyaltyRedeemPoints() != null && request.loyaltyRedeemPoints() > 0L) {
      throw new OfflineReplayValidationException(
          "offlineReplay does not allow a loyaltyRedeemPoints redemption");
    }
    if (request.giftCardId() != null) {
      throw new OfflineReplayValidationException("offlineReplay does not allow a gift card");
    }
    if (request.giftCardRedeemMinor() != null && request.giftCardRedeemMinor() > 0L) {
      throw new OfflineReplayValidationException(
          "offlineReplay does not allow a giftCardRedeemMinor redemption");
    }
  }
}
