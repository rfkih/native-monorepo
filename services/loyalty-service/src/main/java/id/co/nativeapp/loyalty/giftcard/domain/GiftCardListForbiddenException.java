package id.co.nativeapp.loyalty.giftcard.domain;

/**
 * Thrown when a non-owner/manager caller attempts the admin gift-card listing (security review W-4)
 * — {@code GiftCardReader#list()} returns EVERY card for the tenant, codes included, which is
 * bearer-credential enumeration (whoever reads a code can redeem it). A per-code POS lookup ({@code
 * GET /api/v1/loyalty/gift-cards/{code}}) stays ungated — a cashier presenting/scanning ONE
 * already-known code is the normal redemption flow, not enumeration → {@code 403}.
 */
public class GiftCardListForbiddenException extends RuntimeException {

  public GiftCardListForbiddenException() {
    super("Gift-card listing requires the owner or manager role");
  }
}
