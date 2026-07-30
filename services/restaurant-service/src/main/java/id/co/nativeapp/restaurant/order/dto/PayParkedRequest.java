package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;

/**
 * Request body for {@code POST /api/v1/orders/{id}/pay} — finalises a PARKED order by recording the
 * Sale and optional payment.
 *
 * <p>This is the moment revenue is recognised for a parked order, and (Phase 3, ADR 0026) the
 * moment promotions are evaluated — a parked order does NOT lock in promotions at park time; they
 * are re-evaluated here, against the rules/coupon effective at THIS instant. The payment field is
 * optional; when omitted the order is completed without capturing a tender (the same behaviour as a
 * checkout with no payment field).
 *
 * @param payment optional payment instruction; when present a cash tender captures synchronously or
 *     a digital tender creates a PENDING payment (identical to the checkout payment path)
 * @param couponCode Phase 3 (ADR 0026): optional coupon code to redeem at pay time;
 *     case-insensitive. An invalid or exhausted code is rejected ({@code 422}/{@code 409}) —
 *     pay-parked is money-moving, same as checkout.
 */
public record PayParkedRequest(@Valid PaymentRequest payment, String couponCode) {

  /** Convenience for paying with no tender and no coupon (order completes without a capture). */
  public PayParkedRequest() {
    this(null, null);
  }

  /** Convenience for paying with a tender but no coupon (pre-Phase-3 one-arg shape). */
  public PayParkedRequest(@Valid PaymentRequest payment) {
    this(payment, null);
  }
}
