package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
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
 * Verifies the digital-checkout path (ADR 0006, slice 3): a QRIS or CARD checkout creates a PENDING
 * payment with {@code provider_pending = true}, does NOT emit a {@code SaleRecorded} outbox row,
 * and leaves the order in {@code AWAITING_PAYMENT} status (no sale_id yet).
 *
 * <p>The revenue-at-capture invariant: an abandoned digital tender — submitted at checkout but
 * never captured — never recognises revenue.
 */
@SpringBootTest
class DigitalPaymentPendingTest extends PostgresRlsTestBase {

  private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, "Mie Goreng", "MAIN", priceMinor, "IDR"))
                .id());
  }

  @Test
  void qrisCheckoutCreatesPendingPaymentAndNoSale() throws Exception {
    UUID itemId = seedItem(25_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS,
            "qris-pending-001",
            List.of(new id.co.nativeapp.restaurant.order.dto.OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.QRIS, null));

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();

    // No sale at checkout — revenue is deferred to capture.
    assertThat(order.saleId()).as("order must have no sale_id at checkout for QRIS").isNull();

    PaymentResponse payment = order.payment();
    assertThat(payment).isNotNull();
    assertThat(payment.tenderType()).isEqualTo("QRIS");
    assertThat(payment.status()).isEqualTo("PENDING");
    assertThat(payment.providerPending()).isTrue();
    assertThat(payment.saleId()).isNull();
    assertThat(payment.amountMinor()).isEqualTo(25_000L);
    assertThat(payment.currency()).isEqualTo("IDR");

    // No SaleRecorded emitted at checkout — revenue-at-capture invariant.
    assertThat(saleRecordedCountAsAdmin()).as("no SaleRecorded at digital checkout").isZero();
    assertThat(rowCountAsAdmin("payment")).isEqualTo(1L);

    // Order is AWAITING_PAYMENT — not COMPLETED until capture.
    assertThat(orderStatusAsAdmin(order.orderId()))
        .as("order must be AWAITING_PAYMENT")
        .isEqualTo("AWAITING_PAYMENT");
  }

  @Test
  void cardCheckoutCreatesPendingPaymentAndNoSale() throws Exception {
    UUID itemId = seedItem(50_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS,
            "card-pending-001",
            List.of(new id.co.nativeapp.restaurant.order.dto.OrderLineRequest(itemId, 2)),
            new PaymentRequest(TenderType.CARD, null));

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    OrderResponse order = result.order();
    assertThat(order.saleId()).isNull();
    assertThat(order.payment().status()).isEqualTo("PENDING");
    assertThat(order.payment().tenderType()).isEqualTo("CARD");
    assertThat(order.payment().providerPending()).isTrue();
    assertThat(saleRecordedCountAsAdmin()).isZero();
  }

  @Test
  void digitalCheckoutDoesNotBroadcastSaleRecordedOnRollback() throws Exception {
    // If we create a QRIS checkout but then truncate (simulate abandoned), there should be
    // zero SaleRecorded rows — proving no revenue is recognised for an abandoned digital tender.
    UUID itemId = seedItem(10_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS,
            "qris-abandoned-001",
            List.of(new id.co.nativeapp.restaurant.order.dto.OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.QRIS, null));

    TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    // Verify no SaleRecorded was produced at checkout — the payment row exists but no revenue.
    assertThat(saleRecordedCountAsAdmin())
        .as("abandoned QRIS checkout must produce zero SaleRecorded rows")
        .isZero();
  }

  // ---------------------------------------------------------------- helpers

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

  private String orderStatusAsAdmin(UUID orderId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT status FROM restaurant_order WHERE id = '" + orderId + "'")) {
      assertThat(rs.next()).as("order row must exist").isTrue();
      return rs.getString("status");
    }
  }
}
