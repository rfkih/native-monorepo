package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;
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
 */
public record CheckoutRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    @Valid PaymentRequest payment) {

  /** Convenience for a checkout with no payment block (the original three-arg shape). */
  public CheckoutRequest(UUID businessId, String idempotencyKey, List<OrderLineRequest> lines) {
    this(businessId, idempotencyKey, lines, null);
  }
}
