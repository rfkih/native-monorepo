package id.co.nativeapp.barbershop.loyaltyref.domain;

import java.util.UUID;

/**
 * Thrown at checkout when a requested gift-card redemption cannot be atomically decremented from
 * the LOCAL {@code gift_card_ref} cache (ADR 0027, Phase 4) — the card is UNKNOWN to this
 * vertical's cache, not currently {@code ACTIVE}, its currency does not match the sale, or a
 * concurrent redemption raced ours between the clamp read and the atomic decrement. All four are
 * the same wire problem (the card cannot be redeemed against right now) and share one
 * exception/response shape — documented per the task's pinned semantics, not a bug.
 *
 * <p>Maps to {@code 409 Conflict} RFC-7807 ({@code gift-card-unusable}) via {@link
 * id.co.nativeapp.barbershop.config.LoyaltyRefAdvice}. {@code giftCardId} is an opaque UUID — not
 * PII (rule 6) — safe to log/echo.
 */
public class GiftCardUnusableException extends RuntimeException {

  /** Stable RFC-7807 problem type URI — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/gift-card-unusable";

  private final UUID giftCardId;

  public GiftCardUnusableException(UUID giftCardId) {
    super(
        "Gift card "
            + giftCardId
            + " is unknown to this outlet's cache, not ACTIVE, currency-mismatched, or its"
            + " balance is insufficient for the requested redemption");
    this.giftCardId = giftCardId;
  }

  public UUID getGiftCardId() {
    return giftCardId;
  }
}
