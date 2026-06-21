package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
 * @param discountMinor optional order-level fixed discount in minor currency units (&ge; 0). When
 *     non-null, passed to the pricing formula as a fixed discount (overrides any percent discount).
 *     Clamped to &le; subtotal by {@link
 *     id.co.nativeapp.restaurant.pricing.service.TaxChargeService} so the discount can never exceed
 *     the order subtotal. A {@code null} value means no discount (same as passing 0).
 */
public record CheckoutRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    @Valid PaymentRequest payment,
    @Min(0) Long discountMinor) {

  /** Convenience for a checkout with no payment and no discount (the original three-arg shape). */
  public CheckoutRequest(UUID businessId, String idempotencyKey, List<OrderLineRequest> lines) {
    this(businessId, idempotencyKey, lines, null, null);
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
    this(businessId, idempotencyKey, lines, payment, null);
  }
}
