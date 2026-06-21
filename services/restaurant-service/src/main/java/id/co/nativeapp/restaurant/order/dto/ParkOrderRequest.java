package id.co.nativeapp.restaurant.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/orders/park} — saves a cart as a parked order.
 *
 * <p>A parked order records NO sale, NO payment, and NO revenue. Revenue is recognised only when
 * {@code POST /api/v1/orders/{id}/pay} is called to finalise the order. The {@code orderType}
 * defaults to {@code DINE_IN} when omitted. {@code tableId} is only valid for {@code DINE_IN}
 * orders; supplying it for {@code TAKEAWAY} or {@code DELIVERY} is a 400 error.
 *
 * <p>The tenant ({@code company_id}) and actor are intentionally absent — they come from the bound
 * {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the client (rule 5).
 *
 * @param businessId the originating business unit
 * @param idempotencyKey the client's request id; dedupe key with company_id
 * @param lines the cart lines; must be non-empty with qty &ge; 1 per line
 * @param orderType DINE_IN / TAKEAWAY / DELIVERY; defaults to DINE_IN when null
 * @param tableId optional table reference; only valid for DINE_IN
 * @param discountMinor optional order-level fixed discount (&ge; 0 minor units)
 */
public record ParkOrderRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    String orderType,
    UUID tableId,
    @Min(0) Long discountMinor) {

  /** Convenience constructor with no discount, no table, and default DINE_IN type. */
  public ParkOrderRequest(UUID businessId, String idempotencyKey, List<OrderLineRequest> lines) {
    this(businessId, idempotencyKey, lines, null, null, null);
  }
}
