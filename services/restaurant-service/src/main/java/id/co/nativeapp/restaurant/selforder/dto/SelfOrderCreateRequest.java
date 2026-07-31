package id.co.nativeapp.restaurant.selforder.dto;

import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/self-order/orders} — the ANONYMOUS diner's cart (Phase 6,
 * ADR 0029). Deliberately carries NOTHING about who/where: {@code businessId}/{@code outletId}/
 * {@code tableLabel} all come from the verified self-order token ({@link
 * id.co.nativeapp.restaurant.config.SelfOrderPrincipal}, bound by {@link
 * id.co.nativeapp.restaurant.config.SelfOrderTokenFilter}), never from the body — an anonymous
 * caller cannot claim to be at a different outlet/table than the QR they actually scanned.
 *
 * <p><strong>Bounded input (this is the fleet's only UNAUTHENTICATED write — security review
 * F-2).</strong> {@code lines} is capped at {@value #MAX_LINES} and each line's {@code qty} at
 * {@value SelfOrderLineBounds#MAX_QTY} (bean validation, rejected {@code 400} before the service
 * runs) so a crafted body cannot exhaust the heap on deserialization or overflow the price
 * arithmetic into a 500. The cap is on THIS DTO only — the authenticated checkout path is
 * unaffected. A servlet request-body size cap backstops the parse itself (see application.yml).
 *
 * @param idempotencyKey the client's request id; dedupe key with company_id (the diner's device
 *     generates this once and retries with the same value on a network failure)
 * @param lines the cart lines; non-empty, at most {@value #MAX_LINES}, qty &ge; 1 per line — prices
 *     are ALWAYS server-resolved from the current menu, never trusted from the client
 */
public record SelfOrderCreateRequest(
    @NotBlank String idempotencyKey,
    @NotEmpty @Size(max = MAX_LINES) @Valid List<@Valid SelfOrderLineBounds> lines) {

  /** Max distinct cart lines an anonymous self-order may carry. */
  public static final int MAX_LINES = 100;

  /**
   * Maps the bounded self-order lines onto the shared {@link OrderLineRequest} the writer takes.
   */
  public List<OrderLineRequest> toOrderLines() {
    return lines.stream()
        .map(l -> new OrderLineRequest(l.menuItemId(), l.qty(), l.selectedOptionIds()))
        .toList();
  }
}
