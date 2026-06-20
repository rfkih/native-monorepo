package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
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
 * Acceptance test for the digital-capture flow (ADR 0006, slice 3).
 *
 * <p>Proves the full lifecycle: a QRIS/CARD checkout produces a PENDING payment with no sale →
 * capture emits exactly one {@code SaleRecorded} + transitions the payment to CAPTURED + links the
 * order's {@code sale_id} + moves the order to COMPLETED. Also verifies the receipt endpoint
 * returns the correct state after capture.
 */
@SpringBootTest
class PaymentCaptureAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String ACTOR = "cashier-c@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService captureService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, "Es Teh", "DRINK", priceMinor, "IDR"))
                .id());
  }

  @Test
  void qrisCaptureEmitsSaleRecordedAndCompletesOrder() throws Exception {
    UUID itemId = seedItem(8_000L);
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "capture-qris-001",
                        List.of(new OrderLineRequest(itemId, 3)), // total 24_000
                        new PaymentRequest(TenderType.QRIS, null))));

    UUID paymentId = checkout.order().payment().paymentId();

    // Pre-capture: no SaleRecorded, no sale_id on order.
    assertThat(saleRecordedCountAsAdmin()).isZero();

    // Capture.
    PaymentResponse captured =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    assertThat(captured.status()).isEqualTo("CAPTURED");
    assertThat(captured.tenderType()).isEqualTo("QRIS");
    assertThat(captured.saleId()).isNotNull();
    assertThat(captured.amountMinor()).isEqualTo(24_000L);
    assertThat(captured.currency()).isEqualTo("IDR");
    assertThat(captured.providerPending()).isTrue(); // flagged-pending marker stays

    // Exactly one SaleRecorded after capture.
    assertThat(saleRecordedCountAsAdmin())
        .as("exactly one SaleRecorded after capture")
        .isEqualTo(1L);

    // Order is COMPLETED and has a sale_id.
    String[] orderState = orderStateAsAdmin(checkout.order().orderId());
    assertThat(orderState[0]).as("order status must be COMPLETED").isEqualTo("COMPLETED");
    assertThat(orderState[1]).as("order must have sale_id after capture").isNotNull();
  }

  @Test
  void captureReceiptReturnsCorrectState() throws Exception {
    UUID itemId = seedItem(12_000L);
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "capture-receipt-001",
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.CARD, null))));

    UUID paymentId = checkout.order().payment().paymentId();
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    // Read the receipt.
    PaymentResponse receipt =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.getReceipt(paymentId));

    assertThat(receipt.paymentId()).isEqualTo(paymentId);
    assertThat(receipt.status()).isEqualTo("CAPTURED");
    assertThat(receipt.tenderType()).isEqualTo("CARD");
    assertThat(receipt.amountMinor()).isEqualTo(12_000L);
    assertThat(receipt.saleId()).isNotNull();
  }

  @Test
  void receiptForPendingPaymentShowsPendingState() throws Exception {
    UUID itemId = seedItem(5_000L);
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "pending-receipt-001",
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.QRIS, null))));

    UUID paymentId = checkout.order().payment().paymentId();

    // Receipt before capture.
    PaymentResponse receipt =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.getReceipt(paymentId));

    assertThat(receipt.status()).isEqualTo("PENDING");
    assertThat(receipt.saleId()).isNull();
  }

  @Test
  void capturingCashPaymentIsRejected() throws Exception {
    UUID itemId = seedItem(10_000L);
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "cash-no-capture-001",
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.CASH, 10_000L))));

    UUID paymentId = checkout.order().payment().paymentId();

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digital");
  }

  // ---------------------------------------------------------------- helpers

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

  /** Returns [status, sale_id] for the given order row. */
  private String[] orderStateAsAdmin(UUID orderId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status, sale_id::text FROM restaurant_order WHERE id = '"
                    + orderId
                    + "'")) {
      assertThat(rs.next()).as("order row must exist").isTrue();
      return new String[] {rs.getString("status"), rs.getString("sale_id")};
    }
  }
}
