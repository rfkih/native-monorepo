package id.co.nativeapp.restaurant.selforder;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRedisTestBase;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipal;
import id.co.nativeapp.restaurant.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkedOrderSummary;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest;
import id.co.nativeapp.restaurant.selforder.service.SelfOrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The lazy self-order sweep (Phase 6, ADR 0029): a stale {@code PARKED SELF_ORDER} row is moved to
 * the terminal, non-money-moving {@code EXPIRED} status on the NEXT self-order create for that
 * outlet — there is no {@code @Scheduled} job anywhere in this fleet, so the sweep runs inline
 * (before the cap check / insert) in {@code OrderWriter.parkSelfOrder}.
 *
 * <p>{@code native.self-order.expiry} is overridden to {@code PT0S} for this class ONLY, so the
 * FIRST parked row is already "stale" by the time the SECOND create call runs (no real 30-minute
 * wait, no {@code Thread.sleep}: {@code occurred_at} is set at INSERT time, and any later
 * {@code Instant.now()} is strictly after it).
 */
@SpringBootTest
class SelfOrderSweepTest extends PostgresRedisTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String SELF_ORDER = "self_order";
  private static final String OWNER_ACTOR = "owner@example.co.id";

  @DynamicPropertySource
  static void expiryProperty(DynamicPropertyRegistry registry) {
    registry.add("native.self-order.expiry", () -> "PT0S");
  }

  @Autowired private MenuService menuService;
  @Autowired private SelfOrderService selfOrderService;
  @Autowired private OrderService orderService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @BeforeEach
  void bindSelfOrderPrincipal() {
    MockHttpServletRequest mockRequest = new MockHttpServletRequest();
    mockRequest.setAttribute(
        SelfOrderPrincipal.REQUEST_ATTRIBUTE, new SelfOrderPrincipal(OUTLET, OUTLET, "T1"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @Test
  void aStaleParkedSelfOrderRowIsExpiredOnTheNextCreateAndDropsOutOfTheParkedTray() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, SELF_ORDER, true));
    UUID itemId =
        TenantContext.callAs(
                TENANT,
                OWNER_ACTOR,
                () ->
                    menuService.createItem(
                        new CreateMenuItemRequest(OUTLET, "Kopi", "DRINK", 12_000L, "IDR")))
            .id();

    // First create: parks a row that (under PT0S expiry) is already stale relative to any LATER
    // Instant.now().
    OrderResponse first = create(itemId, "k-stale");
    assertThat(first.status()).isEqualTo("PARKED");
    assertThat(rowStatusAsAdmin(first.orderId())).isEqualTo("PARKED");

    // Second create: OrderWriter.parkSelfOrder sweeps this outlet's stale PARKED SELF_ORDER rows
    // BEFORE inserting the new one.
    OrderResponse second = create(itemId, "k-fresh");
    assertThat(second.status()).isEqualTo("PARKED");

    // The first row is now EXPIRED — a terminal, non-money-moving status.
    assertThat(rowStatusAsAdmin(first.orderId())).isEqualTo("EXPIRED");

    // The ParkedTray (findParkedViewsByBusinessId filters status = 'PARKED') no longer shows it —
    // only the second, fresh row.
    List<ParkedOrderSummary> parked =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> orderService.listParked(OUTLET));
    assertThat(parked).extracting(ParkedOrderSummary::orderId).containsExactly(second.orderId());
  }

  private OrderResponse create(UUID itemId, String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT,
        "self-order:T1",
        () ->
            selfOrderService.createOrder(
                new SelfOrderCreateRequest(
                    idempotencyKey, List.of(new OrderLineRequest(itemId, 1)))));
  }

  private String rowStatusAsAdmin(UUID orderId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT status FROM restaurant_order WHERE id = '" + orderId + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
