package id.co.nativeapp.barbershop.loyaltyref.service;

import id.co.nativeapp.barbershop.loyaltyref.domain.GiftCardUnusableException;
import id.co.nativeapp.barbershop.loyaltyref.domain.LoyaltyBalanceInsufficientException;
import id.co.nativeapp.barbershop.loyaltyref.repository.GiftCardBalance;
import id.co.nativeapp.barbershop.loyaltyref.repository.GiftCardRefRepository;
import id.co.nativeapp.barbershop.loyaltyref.repository.MemberBalanceRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Checkout-time loyalty-points and gift-card redemption: clamps a requested redemption to the
 * LOCAL cache and atomically decrements it in the SAME database transaction as the checkout (ADR
 * 0027 decisions 2 and 3) — never a synchronous call to loyalty-service (rule 2).
 *
 * <p>Not itself {@code @Transactional} — like {@code outletref.service.OutletAccessGuard}, this
 * bean's methods must be called from inside the caller's own {@code @Transactional} boundary
 * (checkout) so the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} has already set the tenant
 * GUC and the atomic decrement participates in the checkout's single physical transaction: a
 * checkout that fails downstream (e.g. insufficient stock) rolls back the decrement together with
 * everything else.
 *
 * <p><strong>Clamp order (pinned, ADR 0027):</strong> a redemption is clamped to {@code min(requested,
 * cached balance, remaining deductible base)} — never negative, never exceeding what remains. The
 * clamp is computed from an upper-bound READ of the cache; the actual double-spend guard is the
 * atomic decrement's {@code WHERE} clause. Zero rows updated (the member/card is unknown to this
 * cache, or a concurrent redemption already consumed the balance since the read) maps to {@code
 * 409} — {@link LoyaltyBalanceInsufficientException} / {@link GiftCardUnusableException}.
 *
 * <p><strong>Idempotent replay.</strong> Callers MUST invoke this guard only on a genuinely NEW
 * checkout — every writer's idempotency fast path returns BEFORE calling this guard, so a
 * re-delivered request never double-decrements (verified per-writer, per the task's pinned
 * ordering requirement).
 */
@Component
public class LoyaltyRedemptionGuard {

  private final MemberBalanceRefRepository memberBalanceRefRepository;
  private final GiftCardRefRepository giftCardRefRepository;

  public LoyaltyRedemptionGuard(
      MemberBalanceRefRepository memberBalanceRefRepository,
      GiftCardRefRepository giftCardRefRepository) {
    this.memberBalanceRefRepository = memberBalanceRefRepository;
    this.giftCardRefRepository = giftCardRefRepository;
  }

  /**
   * Phase 4 (ADR 0027): rejects a request that supplies one half of a redemption pair without the
   * other — {@code loyaltyRedeemPoints > 0} requires {@code loyaltyMemberId}, and {@code
   * giftCardId} requires a positive {@code giftCardRedeemMinor} (and vice versa). A {@code
   * loyaltyMemberId} with no redemption is legal (EARN-only attribution); a bare {@code
   * giftCardId}/{@code giftCardRedeemMinor} without its pair is not, since a gift card carries no
   * earn concept.
   */
  public static void validatePairing(
      UUID loyaltyMemberId, Long loyaltyRedeemPoints, UUID giftCardId, Long giftCardRedeemMinor) {
    if (loyaltyRedeemPoints != null && loyaltyRedeemPoints > 0L && loyaltyMemberId == null) {
      throw new IllegalArgumentException(
          "loyaltyMemberId is required when loyaltyRedeemPoints > 0");
    }
    boolean giftCardAmountRequested = giftCardRedeemMinor != null && giftCardRedeemMinor > 0L;
    if (giftCardAmountRequested != (giftCardId != null)) {
      throw new IllegalArgumentException(
          "giftCardId and a positive giftCardRedeemMinor must be supplied together");
    }
  }

  /**
   * Redeems loyalty points against {@code memberId}, clamped to {@code min(requestedPoints, cached
   * balance, remainingDeductibleMinor)} (1 point = 1 minor unit, ADR 0027 points-valuation v1).
   *
   * @param memberId the loyalty member, or {@code null} if no member is attached (returns 0)
   * @param requestedPoints the points the till asked to redeem; {@code <= 0} returns 0 (no-op)
   * @param remainingDeductibleMinor the subtotal remaining AFTER the promo-engine discount — the
   *     ceiling a points redemption may never exceed (never negative; a negative input is treated
   *     as 0)
   * @return the ACTUAL points redeemed (and minor units deducted — 1:1), possibly less than
   *     requested; {@code 0} if nothing was redeemed (no member, no request, or the deductible base
   *     is already exhausted)
   * @throws LoyaltyBalanceInsufficientException if the member is unknown to this outlet's cache, or
   *     the atomic decrement lost a race after the clamp read (409)
   */
  public long redeemPoints(UUID memberId, Long requestedPoints, long remainingDeductibleMinor) {
    if (memberId == null || requestedPoints == null || requestedPoints <= 0L) {
      return 0L;
    }
    long ceiling = Math.max(remainingDeductibleMinor, 0L);
    long wanted = Math.min(requestedPoints, ceiling);
    if (wanted <= 0L) {
      return 0L;
    }
    Long cachedBalance = memberBalanceRefRepository.findBalance(memberId).orElse(null);
    if (cachedBalance == null) {
      throw new LoyaltyBalanceInsufficientException(memberId);
    }
    long clamped = Math.min(wanted, Math.max(cachedBalance, 0L));
    if (clamped <= 0L) {
      return 0L;
    }
    String actor = TenantContext.require().actor();
    int rows = memberBalanceRefRepository.decrementIfSufficient(memberId, clamped, actor);
    if (rows == 0) {
      throw new LoyaltyBalanceInsufficientException(memberId);
    }
    return clamped;
  }

  /**
   * Redeems stored value from {@code giftCardId} as a TENDER settlement (never a discount, ADR 0027
   * decision 4), clamped to {@code min(requestedMinor, cached ACTIVE card balance, grandTotalMinor)}.
   *
   * @param giftCardId the gift card being redeemed, or {@code null} if none (returns 0)
   * @param requestedMinor the amount the till asked to redeem; {@code <= 0} returns 0 (no-op)
   * @param grandTotalMinor the sale's grand total — a gift-card redemption may never exceed it
   * @param currencyCode the sale's ISO-4217 currency; a cached card in a different currency is
   *     treated as unusable (no implicit FX, rule 8)
   * @return the ACTUAL amount redeemed, minor units, possibly less than requested; {@code 0} if
   *     nothing was redeemed
   * @throws GiftCardUnusableException if the card is unknown to this outlet's cache, not currently
   *     ACTIVE, currency-mismatched, or the atomic decrement lost a race after the clamp read (409)
   */
  public long redeemGiftCard(
      UUID giftCardId, Long requestedMinor, long grandTotalMinor, String currencyCode) {
    if (giftCardId == null || requestedMinor == null || requestedMinor <= 0L) {
      return 0L;
    }
    long ceiling = Math.max(grandTotalMinor, 0L);
    long wanted = Math.min(requestedMinor, ceiling);
    if (wanted <= 0L) {
      return 0L;
    }
    GiftCardBalance cached = giftCardRefRepository.findActiveBalance(giftCardId).orElse(null);
    if (cached == null) {
      throw new GiftCardUnusableException(giftCardId);
    }
    if (!cached.currency().equalsIgnoreCase(currencyCode)) {
      throw new GiftCardUnusableException(giftCardId);
    }
    long clamped = Math.min(wanted, Math.max(cached.balanceMinor(), 0L));
    if (clamped <= 0L) {
      return 0L;
    }
    String actor = TenantContext.require().actor();
    int rows = giftCardRefRepository.decrementIfSufficient(giftCardId, clamped, actor);
    if (rows == 0) {
      throw new GiftCardUnusableException(giftCardId);
    }
    return clamped;
  }
}
