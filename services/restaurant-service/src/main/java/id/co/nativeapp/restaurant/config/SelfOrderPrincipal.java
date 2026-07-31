package id.co.nativeapp.restaurant.config;

import java.util.UUID;

/**
 * The verified self-order caller's context (Phase 6, ADR 0029) — set as an {@code
 * HttpServletRequest} attribute by {@link SelfOrderTokenFilter} once a token has passed
 * verification, and read by {@link SelfOrderPrincipalProvider} from the {@code
 * selforder.service.SelfOrderService} layer. Mirrors how {@link ActorRolesProvider} threads
 * gateway-header data from the web tier into non-controller beans without changing the shared
 * {@code TenantContext} record.
 *
 * @param businessId the outlet the verified token grants access to (menu-read + order-create)
 * @param outletId the {@code self_order_access.outlet_id} the verified token's {@code kid} belongs
 *     to (== {@code businessId} in restaurant-service today; carried separately for clarity/forward
 *     compat, see {@code selforderaccess.domain.SelfOrderTokenPayload})
 * @param tableLabel the printed table's label from the token, or {@code null} for a kiosk token
 */
public record SelfOrderPrincipal(UUID businessId, UUID outletId, String tableLabel) {

  /** The {@code HttpServletRequest} attribute key {@link SelfOrderTokenFilter} sets this under. */
  public static final String REQUEST_ATTRIBUTE =
      "id.co.nativeapp.restaurant.config.SELF_ORDER_PRINCIPAL";
}
