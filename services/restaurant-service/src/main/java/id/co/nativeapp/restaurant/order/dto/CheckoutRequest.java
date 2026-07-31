package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/orders}. The tenant ({@code company_id}) and actor are
 * intentionally absent — they come from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never from the client (rule 5).
 *
 * @param businessId the originating business unit
 * @param idempotencyKey the client's request id; the producer-idempotency dedupe key
 * @param lines the order lines; must be non-empty with qty &ge; 1 per line
 * @param payment optional payment for an order paid in the same call (cash captures synchronously;
 *     ADR 0006). Omit it to create the order without recording a payment.
 * @param discountMinor optional order-level fixed discount in minor currency units (&ge; 0). This
 *     is ONE INPUT to the Phase-3 promotions engine (ADR 0026) — the manual-discount layer, applied
 *     LAST after every automatic rule and any redeemed coupon, then jointly clamped so the total
 *     discount can never exceed the order subtotal. A {@code null} value means no manual discount
 *     (same as passing 0). A positive value requires the {@code owner}/{@code manager} role ({@code
 *     ManualDiscountGuard}) — a cashier token is rejected with {@code 403}.
 * @param orderType Phase 4: DINE_IN / TAKEAWAY / DELIVERY; defaults to DINE_IN when null
 * @param tableId Phase 4: nullable table reference; only valid for DINE_IN orders
 * @param couponCode Phase 3 (ADR 0026): optional coupon code; case-insensitive. An invalid or
 *     exhausted code is rejected ({@code 422}/{@code 409}) — unlike the quote endpoint, checkout is
 *     money-moving and never silently drops a bad code.
 * @param loyaltyMemberId Phase 4 (ADR 0027): optional loyalty member to attach to this order (earn
 *     attribution, and/or the identity a points redemption is charged against). {@code null} means
 *     no member.
 * @param loyaltyRedeemPoints Phase 4 (ADR 0027): optional points to redeem, clamped server-side to
 *     {@code min(requested, cached balance, remaining deductible base)} — never trusted as-is.
 *     Requires {@code loyaltyMemberId}; {@code null}/0 means no redemption.
 * @param giftCardId Phase 4 (ADR 0027): optional gift card to redeem as a TENDER (never a
 *     discount). {@code null} means no gift card.
 * @param giftCardRedeemMinor Phase 4 (ADR 0027): optional amount to redeem from the gift card,
 *     minor units, clamped server-side to {@code min(requested, cached ACTIVE balance, grand
 *     total)}. Requires {@code giftCardId}; {@code null}/0 means no redemption.
 * @param offlineReplay Phase 5 (ADR 0028): {@code true} when this checkout is a replay of a sale
 *     queued while the POS device was offline; {@code null}/{@code false} is a normal online
 *     checkout. Offline replay is CASH-only, quick-sale-only: it additionally REQUIRES a {@code
 *     payment} with {@code tenderType == CASH} and forbids {@code couponCode}, a positive {@code
 *     loyaltyRedeemPoints}, and any gift-card redemption ({@code loyaltyMemberId} alone — earn
 *     attribution — remains allowed). A violation is rejected {@code 422}.
 * @param clientOccurredAt Phase 5 (ADR 0028): accepted ONLY when {@code offlineReplay} is {@code
 *     true} — when the sale actually happened on the device, bounded to &le; 48h in the past and
 *     &le; 5min in the future (clock skew); a value outside that window, or supplied with {@code
 *     offlineReplay} not {@code true}, is rejected {@code 422}. When accepted it becomes the
 *     order's {@code occurredAt} (and the {@code SaleRecorded} event's {@code occurred_at}) so the
 *     GL period reflects the day the sale actually happened, not the day it synced. {@code null}
 *     with {@code offlineReplay=true} is allowed and falls back to {@code now()}.
 */
public record CheckoutRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    @Valid PaymentRequest payment,
    @Min(0) Long discountMinor,
    String orderType,
    UUID tableId,
    String couponCode,
    UUID loyaltyMemberId,
    @Min(0) Long loyaltyRedeemPoints,
    UUID giftCardId,
    @Min(0) Long giftCardRedeemMinor,
    Boolean offlineReplay,
    Instant clientOccurredAt) {

  /** Convenience for a checkout with no payment and no discount (the original three-arg shape). */
  public CheckoutRequest(UUID businessId, String idempotencyKey, List<OrderLineRequest> lines) {
    this(
        businessId,
        idempotencyKey,
        lines,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience for a checkout with a payment but no discount (backwards-compatible four-arg
   * shape).
   */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      List<OrderLineRequest> lines,
      PaymentRequest payment) {
    this(
        businessId,
        idempotencyKey,
        lines,
        payment,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience for a checkout with a payment and a discount but no Phase 4/Phase 3 fields
   * (backwards-compatible five-arg shape).
   */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      List<OrderLineRequest> lines,
      PaymentRequest payment,
      Long discountMinor) {
    this(
        businessId,
        idempotencyKey,
        lines,
        payment,
        discountMinor,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Convenience for a checkout with Phase 4 fields but no coupon (pre-Phase-3 seven-arg shape). */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      List<OrderLineRequest> lines,
      PaymentRequest payment,
      Long discountMinor,
      String orderType,
      UUID tableId) {
    this(
        businessId,
        idempotencyKey,
        lines,
        payment,
        discountMinor,
        orderType,
        tableId,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience for a full pre-Phase-5 checkout (ADR 0027 loyalty/gift-card fields, no offline
   * fields — the pre-ADR-0028 twelve-arg canonical shape).
   */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      List<OrderLineRequest> lines,
      PaymentRequest payment,
      Long discountMinor,
      String orderType,
      UUID tableId,
      String couponCode,
      UUID loyaltyMemberId,
      Long loyaltyRedeemPoints,
      UUID giftCardId,
      Long giftCardRedeemMinor) {
    this(
        businessId,
        idempotencyKey,
        lines,
        payment,
        discountMinor,
        orderType,
        tableId,
        couponCode,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
        null,
        null);
  }

  /**
   * Convenience for a checkout with a coupon but no Phase 4 (ADR 0027) loyalty/gift-card fields
   * (pre-Phase-4 eight-arg shape).
   */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      List<OrderLineRequest> lines,
      PaymentRequest payment,
      Long discountMinor,
      String orderType,
      UUID tableId,
      String couponCode) {
    this(
        businessId,
        idempotencyKey,
        lines,
        payment,
        discountMinor,
        orderType,
        tableId,
        couponCode,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
