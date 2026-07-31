package id.co.nativeapp.restaurant.selforder.dto;

import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/self-order/orders} — the ANONYMOUS diner's cart (Phase 6,
 * ADR 0029). Deliberately carries NOTHING about who/where: {@code businessId}/{@code outletId}/
 * {@code tableLabel} all come from the verified self-order token ({@link
 * id.co.nativeapp.restaurant.config.SelfOrderPrincipal}, bound by {@link
 * id.co.nativeapp.restaurant.config.SelfOrderTokenFilter}), never from the body — an anonymous
 * caller cannot claim to be at a different outlet/table than the QR they actually scanned.
 *
 * @param idempotencyKey the client's request id; dedupe key with company_id (the diner's device
 *     generates this once and retries with the same value on a network failure)
 * @param lines the cart lines; must be non-empty with qty &ge; 1 per line — prices are ALWAYS
 *     server-resolved from the current menu, never trusted from the client
 */
public record SelfOrderCreateRequest(
    @NotBlank String idempotencyKey, @NotEmpty @Valid List<OrderLineRequest> lines) {}
