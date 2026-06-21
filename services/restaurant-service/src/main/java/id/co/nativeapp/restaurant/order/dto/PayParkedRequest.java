package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;

/**
 * Request body for {@code POST /api/v1/orders/{id}/pay} — finalises a PARKED order by recording the
 * Sale and optional payment.
 *
 * <p>This is the moment revenue is recognised for a parked order. The payment field is optional;
 * when omitted the order is completed without capturing a tender (the same behaviour as a checkout
 * with no payment field).
 *
 * @param payment optional payment instruction; when present a cash tender captures synchronously or
 *     a digital tender creates a PENDING payment (identical to the checkout payment path)
 */
public record PayParkedRequest(@Valid PaymentRequest payment) {

  /** Convenience for paying with no tender (order completes without a captured payment). */
  public PayParkedRequest() {
    this(null);
  }
}
