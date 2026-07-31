package id.co.nativeapp.restaurant.selforderaccess.domain;

import java.util.UUID;

/**
 * The self-order QR token's decoded claims (Phase 6, ADR 0029): {@code {v, kid, companyId,
 * businessId, outletId, tableLabel}}. {@code businessId} and {@code outletId} carry the SAME value
 * in restaurant-service today — the org tree is flattened (ADR 0012: an outlet IS the business
 * unit's one physical selling location, and {@code restaurant_order}/{@code restaurant_table}/{@code
 * menu_item} all key on that single {@code business_id}) — but both claims are carried on the wire
 * so the token format stays meaningful for a future vertical whose "business" and "outlet" concepts
 * diverge.
 *
 * @param v the payload version (currently always {@code 1}; a filter rejects any other value —
 *     leaves room to evolve the claim shape without breaking already-printed QR codes)
 * @param kid the {@code self_order_access} row's short id — identifies WHICH secret signed this
 *     token, so a rotation (new kid) invalidates every token minted under the retired kid
 * @param companyId the owning tenant; the filter binds {@link id.co.nativeapp.tenant.TenantContext}
 *     from this claim ONLY after it has been proven consistent with a real, ACTIVE {@code
 *     self_order_access} row (RLS-scoped lookup) — never trusted standing alone
 * @param businessId the outlet the token grants menu-read / order-create access to (== {@code
 *     outletId} in restaurant-service)
 * @param outletId the {@code self_order_access.outlet_id} the {@code kid} is scoped to
 * @param tableLabel the printed table's label, or {@code null} for a kiosk (no specific table)
 */
public record SelfOrderTokenPayload(
    int v, String kid, String companyId, UUID businessId, UUID outletId, String tableLabel) {

  /** The only supported payload version today. */
  public static final int CURRENT_VERSION = 1;
}
