package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.StockService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 5 (ADR 0028) — the offline-replay stock policy: {@code StockDeductionWriter
 * #deductForLinesAllowingNegative} never rejects a replayed offline sale for insufficient stock; it
 * deducts allowing the level to go negative and records a discrepancy to the {@code error_log}
 * inbox. Online checkout (the existing guarded path) is UNCHANGED — proven as a same-shape
 * regression against the identical stock setup.
 */
@SpringBootTest
class OfflineStockDiscrepancyTest extends PostgresRlsTestBase {

  private static final String TENANT = "02020202-0202-0202-0202-020202020202";
  private static final String ACTOR = "cashier-offline-stock@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private StockService stockService;
  @Autowired private OrderService orderService;

  @Test
  void offlineReplaySucceedsAndGoesNegativeAndRecordsADiscrepancy() throws Exception {
    UUID itemId = createMenuItem("Nasi Uduk", 10_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 2));

    // Requesting 5 when only 2 are in stock.
    CheckoutRequest req = offlineCheckout("offline-stock-001", itemId, 5);
    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().totalMinor()).isEqualTo(50_000L); // money recorded regardless

    // Stock allowed to go negative: 2 - 5 = -3.
    Integer remaining = stockQuantityAsAdmin(itemId);
    assertThat(remaining).isEqualTo(-3);

    // A discrepancy was recorded to the error-log inbox — exactly one row for this tenant, with
    // enough context (item name, requested vs. available) for repair by count. The order/item ids
    // are NOT asserted verbatim in the message: ErrorMessageRedactor masks any digit run of 10+
    // characters (NIK/bank-account heuristic, HR-6), and a random UUID has a non-trivial chance of
    // containing such a run, so asserting on it would be a flaky test — company_id (a separate,
    // never-redacted column) is the exact-match proof instead.
    assertThat(errorLogRowCountAsAdmin()).isEqualTo(1L);
    String message = discrepancyMessageAsAdmin();
    assertThat(message).contains("Nasi Uduk");
    assertThat(message).contains("requested=5");
    assertThat(message).contains("availableAtCartBuild=2");
    assertThat(discrepancyCompanyIdAsAdmin()).isEqualTo(TENANT);
  }

  @Test
  void offlineReplayOnAnUntrackedItemNeverRecordsADiscrepancy() throws Exception {
    UUID itemId = createMenuItem("Es Teh Tawar", 5_000L);
    // Untracked (stock_quantity NULL) — never a discrepancy, never blocked.

    CheckoutRequest req = offlineCheckout("offline-stock-untracked-001", itemId, 10);
    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(stockQuantityAsAdmin(itemId)).isNull();
    assertThat(errorLogRowCountAsAdmin()).isZero();
  }

  @Test
  void theSameInsufficientStockSaleStillRejectsOnlineRegression() throws Exception {
    UUID itemId = createMenuItem("Bakso Halus", 9_000L);
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, 2));

    // The identical shortfall (5 requested, 2 available), but WITHOUT offlineReplay — online
    // checkout must still reject exactly as before.
    CheckoutRequest onlineReq =
        new CheckoutRequest(
            BUSINESS_ID, "online-stock-001", List.of(new OrderLineRequest(itemId, 5)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(onlineReq)))
        .isInstanceOf(InsufficientStockException.class);

    // Nothing persisted, stock unchanged, no discrepancy recorded (that record is an offline-only
    // concept).
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(stockQuantityAsAdmin(itemId)).isEqualTo(2);
    assertThat(errorLogRowCountAsAdmin()).isZero();
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

  private CheckoutRequest offlineCheckout(String idempotencyKey, UUID itemId, int qty) {
    Instant clientOccurredAt =
        Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1));
    return new CheckoutRequest(
        BUSINESS_ID,
        idempotencyKey,
        List.of(new OrderLineRequest(itemId, qty)),
        new PaymentRequest(TenderType.CASH, 1_000_000L),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        clientOccurredAt,
        null);
  }

  private Integer stockQuantityAsAdmin(UUID itemId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
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

  private long errorLogRowCountAsAdmin() throws Exception {
    return rowCountAsAdmin("error_log");
  }

  private String discrepancyMessageAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT redacted_message FROM error_log"
                    + " WHERE source = 'checkout:offline-stock-discrepancy'")) {
      assertThat(rs.next()).as("expected exactly one discrepancy row").isTrue();
      return rs.getString(1);
    }
  }

  private String discrepancyCompanyIdAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT company_id FROM error_log"
                    + " WHERE source = 'checkout:offline-stock-discrepancy'")) {
      assertThat(rs.next()).as("expected exactly one discrepancy row").isTrue();
      return rs.getString(1);
    }
  }
}
