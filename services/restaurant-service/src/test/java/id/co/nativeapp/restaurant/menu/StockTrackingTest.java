package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.domain.UntrackedStockException;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.StockService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for per-item stock tracking.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>PUT /stock sets absolute quantity; response includes stockQuantity.
 *   <li>POST /stock/add adds a delta to a tracked item; floors at 0 for large negative.
 *   <li>POST /stock/add on untracked item throws UntrackedStockException (→ 422).
 *   <li>GET /menu returns stockQuantity.
 *   <li>Checkout of a tracked item deducts stock atomically.
 *   <li>Checkout exceeding stock throws InsufficientStockException and rolls back (no order, no
 *       sale, no outbox row, no stock change).
 *   <li>Checkout of an untracked item succeeds and does not block.
 * </ul>
 */
@SpringBootTest
class StockTrackingTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private StockService stockService;
  @Autowired private OrderService orderService;

  // ---------------------------------------------------------------------------
  // Stock endpoint tests
  // ---------------------------------------------------------------------------

  @Test
  void setStockReturnsResponseWithUpdatedQuantity() throws Exception {
    UUID itemId = createMenuItem("Ayam Bakar", 25_000L);

    MenuItemResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 50));

    assertThat(response.stockQuantity()).isEqualTo(50);
    assertThat(response.id()).isEqualTo(itemId);
  }

  @Test
  void setStockToNullMakesItemUntracked() throws Exception {
    UUID itemId = createMenuItem("Soto Betawi", 20_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 10));

    MenuItemResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, null));

    assertThat(response.stockQuantity()).isNull();
  }

  @Test
  void addStockIncrementsTrackedQuantity() throws Exception {
    UUID itemId = createMenuItem("Rendang", 35_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 10));

    MenuItemResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> stockService.addStock(itemId, 5));

    assertThat(response.stockQuantity()).isEqualTo(15);
  }

  @Test
  void addStockWithNegativeDeltaFloorsAtZero() throws Exception {
    UUID itemId = createMenuItem("Gado Gado", 18_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 3));

    MenuItemResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> stockService.addStock(itemId, -100));

    assertThat(response.stockQuantity()).isEqualTo(0);
  }

  @Test
  void addStockOnUntrackedItemThrowsUntrackedStockException() throws Exception {
    UUID itemId = createMenuItem("Es Teh", 5_000L);
    // item is untracked by default

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> stockService.addStock(itemId, 10)))
        .isInstanceOf(UntrackedStockException.class)
        .hasMessageContaining("untracked");
  }

  @Test
  void getMenuReturnsStockQuantityField() throws Exception {
    UUID itemId = createMenuItem("Bakso Urat", 15_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 7));

    List<MenuItemResponse> items =
        TenantContext.callAs(
            TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    MenuItemResponse found =
        items.stream()
            .filter(r -> r.id().equals(itemId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Item not found in menu response"));
    assertThat(found.stockQuantity()).isEqualTo(7);
  }

  @Test
  void getMenuReturnsNullStockQuantityForUntrackedItem() throws Exception {
    UUID itemId = createMenuItem("Teh Botol", 8_000L);
    // item left untracked

    List<MenuItemResponse> items =
        TenantContext.callAs(
            TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    MenuItemResponse found =
        items.stream()
            .filter(r -> r.id().equals(itemId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Item not found in menu response"));
    assertThat(found.stockQuantity()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Auto-deduct on sale
  // ---------------------------------------------------------------------------

  @Test
  void checkoutDeductsStockForTrackedItem() throws Exception {
    UUID itemId = createMenuItem("Nasi Uduk", 22_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 10));

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS_ID,
                    "stock-deduct-001",
                    List.of(new OrderLineRequest(itemId, 3)))));

    // Verify stock was deducted: 10 - 3 = 7
    Integer remaining = stockQuantityAsAdmin(itemId);
    assertThat(remaining).isEqualTo(7);
  }

  @Test
  void checkoutDoesNotDeductStockForUntrackedItem() throws Exception {
    UUID itemId = createMenuItem("Kopi Tubruk", 12_000L);
    // item left untracked

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS_ID,
                    "untracked-deduct-001",
                    List.of(new OrderLineRequest(itemId, 5)))));

    // Untracked — stock_quantity remains NULL
    Integer remaining = stockQuantityAsAdmin(itemId);
    assertThat(remaining).isNull();
  }

  @Test
  void checkoutExceedingStockRollsBackEverything() throws Exception {
    UUID itemId = createMenuItem("Pisang Goreng", 10_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 2));

    // Try to buy 5 when only 2 in stock.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        orderService.checkout(
                            new CheckoutRequest(
                                BUSINESS_ID,
                                "stock-overflow-001",
                                List.of(new OrderLineRequest(itemId, 5))))))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("Insufficient stock");

    // Nothing persisted — complete rollback.
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("sale")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();

    // Stock unchanged: still 2.
    Integer remaining = stockQuantityAsAdmin(itemId);
    assertThat(remaining).isEqualTo(2);
  }

  @Test
  void checkoutOfItemAtExactlyZeroStockIsRejected() throws Exception {
    UUID itemId = createMenuItem("Tempe Mendoan", 8_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 0));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        orderService.checkout(
                            new CheckoutRequest(
                                BUSINESS_ID,
                                "stock-zero-001",
                                List.of(new OrderLineRequest(itemId, 1))))))
        .isInstanceOf(InsufficientStockException.class);

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  @Test
  void checkoutDeductsToExactlyZeroLeavesSoldOut() throws Exception {
    UUID itemId = createMenuItem("Lontong Sayur", 14_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 2));

    CheckoutResult result =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS_ID,
                        "stock-exact-001",
                        List.of(new OrderLineRequest(itemId, 2)))));

    assertThat(result.created()).isTrue();
    Integer remaining = stockQuantityAsAdmin(itemId);
    assertThat(remaining).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createMenuItem(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          CreateMenuItemRequest req =
              new CreateMenuItemRequest(BUSINESS_ID, name, "MAIN", priceMinor, "IDR");
          return menuService.createItem(req).id();
        });
  }

  private Integer stockQuantityAsAdmin(UUID itemId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
      rs.next();
      int val = rs.getInt(1);
      return rs.wasNull() ? null : val;
    }
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
