package id.co.nativeapp.carwash.ticket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/carwash/tickets/quote} request body — a stateless price preview: given the
 * requested lines, what would the breakdown be. No ticket is persisted; no side effect.
 *
 * <p>The resulting {@link PriceBreakdownResponse} reflects the currently effective tax/service-
 * charge rules for this tenant PLUS (Phase 3, ADR 0026) every currently-effective automatic
 * promotion and the resolved outcome of {@code couponCode}. A quote NEVER throws for a bad/expired/
 * exhausted coupon code — it reports {@code couponStatus} instead, since it is only a pricing
 * preview.
 *
 * @param businessId the carwash outlet the quote is for
 * @param lines the requested lines; must be non-empty
 * @param discountMinor an optional fixed discount in minor units; {@code null} for no discount —
 *     ONE input to the promotions engine (the manual-discount layer, applied last)
 * @param couponCode Phase 3 (ADR 0026): optional coupon code; case-insensitive. {@code null}/blank
 *     means no coupon.
 * @param loyaltyMemberId Phase 4 (ADR 0027): optional loyalty member to preview a points redemption
 *     against. A quote NEVER throws for an unknown member or an insufficient balance — it silently
 *     previews {@code 0} redeemed (a quote commits nothing).
 * @param loyaltyRedeemPoints Phase 4 (ADR 0027): optional points to preview redeeming.
 * @param giftCardId Phase 4 (ADR 0027): optional gift card to preview a redemption against; same
 *     never-throws posture as {@code loyaltyMemberId}.
 * @param giftCardRedeemMinor Phase 4 (ADR 0027): optional amount to preview redeeming.
 */
public record QuoteRequest(
    @NotNull UUID businessId,
    @NotEmpty List<@Valid TicketLineInput> lines,
    @PositiveOrZero Long discountMinor,
    String couponCode,
    UUID loyaltyMemberId,
    @Min(0) Long loyaltyRedeemPoints,
    UUID giftCardId,
    @Min(0) Long giftCardRedeemMinor) {

  /** Convenience for the pre-Phase-4 four-arg shape (no loyalty/gift-card fields). */
  public QuoteRequest(
      UUID businessId, List<TicketLineInput> lines, Long discountMinor, String couponCode) {
    this(businessId, lines, discountMinor, couponCode, null, null, null, null);
  }
}
