package id.co.nativeapp.loyalty.giftcard.domain;

/**
 * Thrown when a gift-card code does not resolve — unknown, or belonging to another tenant (RLS
 * makes the two indistinguishable) → {@code 404}. Never includes the code in a way that would help
 * enumerate valid codes beyond what the caller already supplied.
 */
public class GiftCardNotFoundException extends RuntimeException {

  public GiftCardNotFoundException(String code) {
    super("No such gift card is accessible: " + code);
  }
}
