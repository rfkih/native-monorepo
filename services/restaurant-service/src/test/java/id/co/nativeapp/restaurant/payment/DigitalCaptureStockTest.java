package id.co.nativeapp.restaurant.payment;

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
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
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
 * Integration tests verifying that stock is deducted EXACTLY ONCE per sale on the digital-capture
 * path.
 *
 * <ul>
 *   <li>Digital checkout of a TRACKED item does NOT change stock at checkout time.
 *   <li>After CAPTURE, stock is deducted by the ordered qty.
 *   <li>A capture that would exceed available stock → 422 {@link InsufficientStockException};
 *       no sale, no SaleRecorded, stock unchanged.
 *   <li>An idempotent re-capture does not deduct twice.
 *   <li>An UNTRACKED item capture leaves stock NULL (no deduction, no error).
 * </ul>
 */
@SpringBootTest
class DigitalCaptureStockTest extends PostgresRlsTestBase {

  private static final String TENANT = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
  private static final String ACTOR = "cashier-a1@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2");

  @Autowired private MenuService menuService;
  @Autowired private StockService stockService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService captureService;

  // ---------------------------------------------------------------------------
  // Test 1: digital checkout does NOT deduct stock; capture does
  // ---------------------------------------------------------------------------

  @Test
  void digitalCheckoutDoesNotDeductStock_captureDeducts() throws Exception {
    UUID itemId = createTrackedItem("Nasi Goreng Digital", 20_000L, 10);

    // Checkout (PENDING) — stock must remain at 10.
    CheckoutResult checkout = doDigitalCheckout(itemId, 3, "digital-stock-001");
    assertThat(stockQuantityAsAdmin(itemId))
        .as("stock must not change at PENDING checkout")
        .isEqualTo(10);
    assertThat(saleRecordedCountAsAdmin()).as("no SaleRecorded yet").isZero();

    // Capture — stock must drop by 3.
    UUID paymentId = checkout.order().payment().paymentId();
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    assertThat(stockQuantityAsAdmin(itemId))
        .as("stock must be deducted after capture: 10 - 3 = 7")
        .isEqualTo(7);
    assertThat(saleRecordedCountAsAdmin()).as("exactly one SaleRecorded after capture").isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // Test 2: capture that exceeds stock → 422, rollback, no sale, stock unchanged
  // ---------------------------------------------------------------------------

  @Test
  void captureExceedingStockRollsBack_noSaleNoStockChange() throws Exception {
    UUID itemId = createTrackedItem("Mie Ayam", 15_000L, 2);

    // Checkout for qty=3 succeeds (stock is not deducted yet; the pre-check only fails at 0).
    // We need qty <= stock at checkout pre-check but > stock at capture.
    // Set stock to 2 AFTER checkout to simulate concurrent depletion.
    // Simpler: checkout qty=2 then set stock to 1 via admin, then try to capture.
    CheckoutResult checkout = doDigitalCheckout(itemId, 2, "digital-stock-overflow-001");
    UUID paymentId = checkout.order().payment().paymentId();

    // Deplete stock to 1 via another (cash) sale so that the digital capture would need 2 but only 1 remains.
    UUID otherItem = createTrackedItem("Dummy", 5_000L, 0);
    // Directly reduce stock via admin SQL to simulate concurrent depletion without another checkout.
    setStockAsAdmin(itemId, 1); // only 1 left, but capture needs 2

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId)))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("Insufficient stock");

    // No sale, no SaleRecorded.
    assertThat(saleRecordedCountAsAdmin()).as("no SaleRecorded on failed capture").isZero();
    assertThat(rowCountAsAdmin("sale")).as("no sale row persisted").isZero();

    // Stock unchanged at 1 (the failed capture rolled back).
    assertThat(stockQuantityAsAdmin(itemId)).as("stock unchanged after failed capture").isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Test 3: idempotent re-capture does not double-deduct
  // ---------------------------------------------------------------------------

  @Test
  void idempotentRecaptureDoesNotDoubleDeductStock() throws Exception {
    UUID itemId = createTrackedItem("Sate Ayam", 25_000L, 5);

    CheckoutResult checkout = doDigitalCheckout(itemId, 2, "digital-stock-idem-001");
    UUID paymentId = checkout.order().payment().paymentId();

    // First capture: deducts 2.
    PaymentResponse first =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(first.status()).isEqualTo("CAPTURED");
    assertThat(stockQuantityAsAdmin(itemId)).as("stock after first capture: 5-2=3").isEqualTo(3);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);

    // Second capture (re-delivery) — must not deduct again, must not emit a second SaleRecorded.
    PaymentResponse second =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(second.status()).isEqualTo("CAPTURED");
    assertThat(second.saleId()).isEqualTo(first.saleId());

    assertThat(stockQuantityAsAdmin(itemId))
        .as("stock must not change on re-capture: still 3")
        .isEqualTo(3);
    assertThat(saleRecordedCountAsAdmin())
        .as("no second SaleRecorded on re-capture")
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // Test 4: untracked item capture leaves stock NULL
  // ---------------------------------------------------------------------------

  @Test
  void captureOfUntrackedItemLeavesStockNull() throws Exception {
    // Create an untracked item (no setStock call).
    UUID itemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS, "Es Jeruk", "DRINK", 8_000L, "IDR"))
                    .id());

    CheckoutResult checkout = doDigitalCheckout(itemId, 2, "digital-untracked-001");
    UUID paymentId = checkout.order().payment().paymentId();

    // Stock must be NULL before and after capture.
    assertThat(stockQuantityAsAdmin(itemId)).as("untracked before capture").isNull();

    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    assertThat(stockQuantityAsAdmin(itemId))
        .as("untracked item — stock_quantity stays NULL after capture")
        .isNull();
    assertThat(saleRecordedCountAsAdmin()).as("SaleRecorded emitted for untracked item").isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createTrackedItem(String name, long priceMinor, int stock) throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(BUSINESS, name, "MAIN", priceMinor, "IDR"))
                    .id());
    TenantContext.callAs(TENANT, ACTOR, () -> stockService.setStock(itemId, stock));
    return itemId;
  }

  private CheckoutResult doDigitalCheckout(UUID itemId, int qty, String idempotencyKey)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS,
                    idempotencyKey,
                    List.of(new OrderLineRequest(itemId, qty)),
                    new PaymentRequest(TenderType.QRIS, null))));
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

  private long saleRecordedCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'")) {
      rs.next();
      return rs.getLong(1);
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

  /** Sets the stock_quantity directly via admin (BYPASSRLS) connection to simulate concurrent depletion. */
  private void setStockAsAdmin(UUID itemId, int qty) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "UPDATE menu_item SET stock_quantity = " + qty + " WHERE id = '" + itemId + "'");
    }
  }
}
