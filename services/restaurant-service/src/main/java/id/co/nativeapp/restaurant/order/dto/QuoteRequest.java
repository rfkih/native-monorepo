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
 * reflects the currently effective tax and service-charge rules for this tenant, PLUS (Phase 3, ADR
 * 0026) every currently-effective automatic promotion and the resolved outcome of {@code
 * couponCode}. A quote NEVER throws for a bad/expired/exhausted coupon code — it reports {@code
 * couponStatus} instead, since it is only a pricing preview.
 *
 * @param businessId the originating business unit (determines which items are in scope)
 * @param lines the cart lines; must be non-empty with qty &ge; 1 per line
 * @param discountMinor optional order-level fixed discount in minor currency units (&ge; 0) — ONE
 *     input to the promotions engine (the manual-discount layer, applied last); clamped to &le;
 *     subtotal jointly with every other applicable promotion. A {@code null} value means no manual
 *     discount.
 * @param couponCode optional coupon code (Phase 3, ADR 0026); case-insensitive. {@code null}/blank
 *     means no coupon.
 */
public record QuoteRequest(
    @NotNull UUID businessId,
    @NotEmpty @Valid List<OrderLineRequest> lines,
    @Min(0) Long discountMinor,
    String couponCode) {

  /** Convenience for a quote with no discount and no coupon (the two-arg shape). */
  public QuoteRequest(UUID businessId, List<OrderLineRequest> lines) {
    this(businessId, lines, null, null);
  }

  /** Convenience for a quote with a discount but no coupon (the pre-Phase-3 three-arg shape). */
  public QuoteRequest(UUID businessId, List<OrderLineRequest> lines, Long discountMinor) {
    this(businessId, lines, discountMinor, null);
  }
}
