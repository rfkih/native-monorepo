package id.co.nativeapp.restaurant.selforder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRedisTestBase;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipal;
import id.co.nativeapp.restaurant.entitlement.domain.NotEntitledException;
import id.co.nativeapp.restaurant.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.StockService;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderLineBounds;
import id.co.nativeapp.restaurant.selforder.service.SelfOrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The self-order-create gate + park-invariant regression (Phase 6, ADR 0029): entitlement
 * fail-closed, and — once entitled — a create writes ONLY a {@code PARKED SELF_ORDER} order (no
 * sale, no payment, no outbox row, no stock movement).
 *
 * <p>Entitlement is seeded synchronously via {@link EntitlementProjectionService#apply} (exactly
 * mirroring barbershop-service's {@code EntitlementGateFailClosedTest} pattern) — no Kafka needed;
 * the Kafka wire path itself is proven separately by {@code
 * entitlement.EntitlementGateConsumeTest}. The token filter is bypassed here — {@link
 * SelfOrderPrincipal} is bound directly as a mock-request attribute (mirrors {@code
 * order.OutletEnforcementTest}'s {@code ActorRolesProvider} binding style) so this test targets the
 * SERVICE-layer gate/park-invariant, not the HTTP filter (covered by {@code
 * SelfOrderTokenFilterTest}).
 */
@SpringBootTest
class SelfOrderCreateGateTest extends PostgresRedisTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String SELF_ORDER = "self_order";
  private static final String OWNER_ACTOR = "owner@example.co.id";

  @Autowired private MenuService menuService;
  @Autowired private StockService stockService;
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
  void createIsRejectedWithNotEntitledWhenTheCompanyHasNoGrant() throws Exception {
    UUID itemId = createMenuItem();

    assertThatThrownBy(() -> create(itemId, "k-not-entitled"))
        .isInstanceOf(NotEntitledException.class);

    // No side effect at all: no order row of any kind.
    assertThat(countAsAdmin("SELECT count(*) FROM restaurant_order")).isZero();
  }

  @Test
  void entitledCreateParksAnOrderWithNoSaleNoPaymentNoOutboxNoStockMovement() throws Exception {
    grantSelfOrder();
    UUID itemId = createMenuItem();
    // Tracked stock — proves the self-order park never deducts it.
    TenantContext.callAs(TENANT, OWNER_ACTOR, () -> stockService.setStock(itemId, 10));

    OrderResponse created = create(itemId, "k-park-1");

    assertThat(created.status()).isEqualTo("PARKED");
    assertThat(created.saleId()).isNull();

    // The park invariant, over the admin (BYPASSRLS) connection: exactly one order row, zero
    // sale/payment/outbox rows.
    assertThat(countAsAdmin("SELECT count(*) FROM restaurant_order")).isEqualTo(1L);
    assertThat(countAsAdmin("SELECT count(*) FROM sale")).isZero();
    assertThat(countAsAdmin("SELECT count(*) FROM payment")).isZero();
    assertThat(countAsAdmin("SELECT count(*) FROM outbox")).isZero();

    // The order row itself is tagged source=SELF_ORDER with the token's table_label snapshot.
    assertThat(stringColumnAsAdmin("restaurant_order", "source")).isEqualTo("SELF_ORDER");
    assertThat(stringColumnAsAdmin("restaurant_order", "table_label")).isEqualTo("T1");

    // Stock unchanged — no deduction on park.
    MenuItemResponse item =
        TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () ->
                menuService.findActiveByBusiness(OUTLET).stream()
                    .filter(i -> i.id().equals(itemId))
                    .findFirst()
                    .orElseThrow());
    assertThat(item.stockQuantity()).isEqualTo(10);
  }

  @Test
  void createIsIdempotentOnTheSameIdempotencyKey() throws Exception {
    grantSelfOrder();
    UUID itemId = createMenuItem();

    OrderResponse first = create(itemId, "k-idem");
    OrderResponse second = create(itemId, "k-idem");

    assertThat(second.orderId()).isEqualTo(first.orderId());
    assertThat(countAsAdmin("SELECT count(*) FROM restaurant_order")).isEqualTo(1L);
  }

  @Test
  void selfOrderIdempotencyKeyDoesNotCollideWithAPosOrderUsingTheSameRawKey() throws Exception {
    grantSelfOrder();
    UUID itemId = createMenuItem();
    String sharedRawKey = "shared-key-" + UUID.randomUUID();

    // A staff POS park using the raw key, on the same tenant/outlet.
    CheckoutResult posParked =
        TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        OUTLET, sharedRawKey, List.of(new OrderLineRequest(itemId, 1)))));

    // A self-order create with the SAME raw key: SelfOrderService namespaces it server-side
    // ("self-order:" + key), so it must NOT idempotently resolve to the POS order above — it
    // parks its own, distinct row.
    OrderResponse selfOrderCreated = create(itemId, sharedRawKey);

    assertThat(selfOrderCreated.orderId()).isNotEqualTo(posParked.order().orderId());
    assertThat(countAsAdmin("SELECT count(*) FROM restaurant_order")).isEqualTo(2L);
    assertThat(idempotencyKeyAsAdmin(posParked.order().orderId())).isEqualTo(sharedRawKey);
    assertThat(idempotencyKeyAsAdmin(selfOrderCreated.orderId()))
        .isEqualTo("self-order:" + sharedRawKey);
  }

  private void grantSelfOrder() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, SELF_ORDER, true, Instant.now()));
  }

  private UUID createMenuItem() throws Exception {
    return TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () ->
                menuService.createItem(
                    new CreateMenuItemRequest(OUTLET, "Nasi Goreng", "MAIN", 25_000L, "IDR")))
        .id();
  }

  private OrderResponse create(UUID itemId, String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT,
        "self-order:T1",
        () ->
            selfOrderService.createOrder(
                new SelfOrderCreateRequest(
                    idempotencyKey, List.of(new SelfOrderLineBounds(itemId, 1, null)))));
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String idempotencyKeyAsAdmin(UUID orderId) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT idempotency_key FROM restaurant_order WHERE id = '" + orderId + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private String stringColumnAsAdmin(String table, String column) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT " + column + " FROM " + table + " LIMIT 1")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
