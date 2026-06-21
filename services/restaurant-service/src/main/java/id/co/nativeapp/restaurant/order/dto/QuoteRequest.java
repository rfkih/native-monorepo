package id.co.nativeapp.restaurant.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/orders/quote}.
 *
 * <p>A quote is a read-only price computation — no order, sale, payment, or outbox row is
 * persisted. The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 *
 * <p>The same item validation as checkout applies: all items must exist, be active, belong to
 * {@code businessId}, and share the same currency. The resulting {@link PriceBreakdownResponse}
 * reflects the currently effective tax and service-charge rules for this tenant.
 *
 * @param businessId the originating business unit (determines which items are in scope)
 * @param lines the cart lines; must be non-empty with qty &ge; 1 per line
 * @param discountMinor optional order-level fixed discount in minor currency units (&ge; 0). Passed
 *     to the pricing formula as a fixed discount; clamped to &le; subtotal. A {@code null} value
 *     means no discount.
 */
public record QuoteRequest(
    @NotNull UUID businessId,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    @Min(0) Long discountMinor) {

  /** Convenience for a quote with no discount (the two-arg shape). */
  public QuoteRequest(UUID businessId, List<OrderLineRequest> lines) {
    this(businessId, lines, null);
  }
}
