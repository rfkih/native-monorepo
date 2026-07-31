package id.co.nativeapp.restaurant.selforder.service;

import id.co.nativeapp.entitlementcheck.EntitlementChecker;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipal;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipalProvider;
import id.co.nativeapp.restaurant.config.SelfOrderProperties;
import id.co.nativeapp.restaurant.entitlement.domain.NotEntitledException;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.service.OrderWriter;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the ANONYMOUS self-order surface (Phase 6, ADR 0029): {@code GET
 * /api/v1/self-order/menu} and {@code POST /api/v1/self-order/orders} — exactly the two things the
 * design allows an anonymous diner to do. Both endpoints run behind {@link
 * id.co.nativeapp.restaurant.config.SelfOrderTokenFilter}, which has already verified the token and
 * bound BOTH {@link TenantContext} (from the verified {@code self_order_access} row's {@code
 * company_id}) and {@link SelfOrderPrincipal} (the outlet/table) before this service is ever
 * reached.
 *
 * <p><strong>Entitlement gate — order-create ONLY.</strong> {@link #createOrder} is gated on the
 * {@code self_order} module ({@link EntitlementChecker}, the fleet's shared Redis-cached gate) —
 * BEFORE any DB write. A company not entitled gets {@link NotEntitledException} (403) and NO row is
 * written. {@link #menu} is deliberately UNGATED, mirroring every other vertical's read-vs-write
 * entitlement asymmetry ({@code barbershop.ticket.service.TicketService.getById} etc.) — a menu read
 * carries no revenue/side-effect risk, and a diner must be able to see what they would be ordering
 * even on a not-yet-entitled trial company.
 *
 * <p>Not itself {@code @Transactional} — the transactional unit of work lives in {@link
 * OrderWriter#parkSelfOrder} so the proxy and the RLS aspect engage (same {@code OrderService}
 * pattern the rest of this feature already follows).
 */
@Service
public class SelfOrderService {

  private final OrderWriter orderWriter;
  private final MenuService menuService;
  private final EntitlementChecker entitlementChecker;
  private final SelfOrderPrincipalProvider principalProvider;
  private final String moduleKey;

  public SelfOrderService(
      OrderWriter orderWriter,
      MenuService menuService,
      EntitlementChecker entitlementChecker,
      SelfOrderPrincipalProvider principalProvider,
      SelfOrderProperties properties) {
    this.orderWriter = orderWriter;
    this.menuService = menuService;
    this.entitlementChecker = entitlementChecker;
    this.principalProvider = principalProvider;
    this.moduleKey = properties.moduleKey();
  }

  /**
   * The verified token's outlet's active menu — reuses the existing cashier menu-read path
   * verbatim, scoped to the token's business. Ungated (see class javadoc).
   */
  public List<MenuItemResponse> menu() {
    SelfOrderPrincipal principal = principalProvider.require();
    return menuService.findActiveByBusiness(principal.businessId());
  }

  /**
   * Creates a PARKED {@code source=SELF_ORDER} order for the verified token's outlet/table.
   * Idempotent on {@code (company_id, idempotencyKey)}, exactly like the staff park path.
   *
   * @throws NotEntitledException if the bound company is not entitled to the {@code self_order}
   *     module (-&gt; 403)
   * @throws id.co.nativeapp.restaurant.selforder.domain.SelfOrderCapExceededException if the
   *     outlet's unconfirmed self-order count is at/over the configured cap (-&gt; 429)
   */
  public OrderResponse createOrder(SelfOrderCreateRequest request) {
    requireEntitled();
    SelfOrderPrincipal principal = principalProvider.require();
    try {
      CheckoutResult result =
          orderWriter.parkSelfOrder(
              principal.businessId(),
              request.lines(),
              request.idempotencyKey(),
              principal.tableLabel());
      return result.order();
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer (e.g. a double-tap retry) already inserted the same idempotency key —
      // re-read the winner's order in a fresh transaction, exactly the OrderService.park recovery.
      return orderWriter
          .findExistingByKey(request.idempotencyKey())
          .orElseThrow(() -> conflict);
    }
  }

  private void requireEntitled() {
    String companyId = TenantContext.require().companyId();
    if (!entitlementChecker.isEntitled(companyId, moduleKey)) {
      throw new NotEntitledException(moduleKey);
    }
  }
}
