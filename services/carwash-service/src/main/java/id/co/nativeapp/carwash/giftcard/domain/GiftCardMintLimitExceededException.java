package id.co.nativeapp.carwash.giftcard.domain;

/**
 * Thrown when a gift-card SELL request's {@code amountMinor} exceeds the configured {@code
 * native.giftcard.max-mint-minor} ceiling (security review W-3) — an unbounded mint would let a
 * compromised/misbehaving POS client create arbitrarily large redeemable liability with no
 * independent check → {@code 422 Unprocessable Entity} ({@code gift-card-mint-limit}).
 */
public class GiftCardMintLimitExceededException extends RuntimeException {

  private final long requestedMinor;
  private final long limitMinor;

  public GiftCardMintLimitExceededException(long requestedMinor, long limitMinor) {
    super(
        "Gift-card mint amount "
            + requestedMinor
            + " exceeds the configured limit of "
            + limitMinor
            + " minor units");
    this.requestedMinor = requestedMinor;
    this.limitMinor = limitMinor;
  }

  public long getRequestedMinor() {
    return requestedMinor;
  }

  public long getLimitMinor() {
    return limitMinor;
  }
}
