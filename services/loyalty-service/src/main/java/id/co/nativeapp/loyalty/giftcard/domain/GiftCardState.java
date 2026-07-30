package id.co.nativeapp.loyalty.giftcard.domain;

/**
 * The {@code gift_card.state} — matches the migration CHECK constraint and the wire vocabulary on
 * {@code GiftCardStateChanged.state}.
 */
public enum GiftCardState {
  ACTIVE,
  DEPLETED,
  EXPIRED,
  VOID
}
