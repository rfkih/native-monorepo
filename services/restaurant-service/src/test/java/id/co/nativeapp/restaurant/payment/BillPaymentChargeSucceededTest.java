package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.restaurant.EventFixtures;
import id.co.nativeapp.restaurant.KafkaPostgresRedisTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * V38 — {@code PaymentChargeSucceeded} (ADR 0045) dispatch to the BILL gateway capture path, driven
 * end-to-end through Kafka (mirrors {@link PaymentChargeSucceededConsumeAcceptanceTest} in shape).
 * Proves {@code PaymentChargeSucceededWriter} routes a bill-originated payment to {@code
 * BillPaymentCaptureWriter} (the bill closes) and a bill-unrelated ORDER payment still routes to
 * the pre-existing {@code PaymentCaptureWriter} (byte-identical to before this change) — both
 * consumed off the SAME topic/consumer, so the dispatch itself is the thing under test.
 */
@SpringBootTest
class BillPaymentChargeSucceededTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "e2000001-e200-e200-e200-e20000000001";
  private static final String ACTOR = "cashier-gw-kafka@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("e2000001-e200-e200-e200-e20000000002");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;
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

  private UUID openBillWithLine(UUID itemId, int qty) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, null, "Kafka Guest")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, qty)))));
    return opened.id();
  }

  @Test
  void settledChargeForABillPaymentCapturesTheCheckAndClosesTheBill() throws Exception {
    UUID itemId = seedItem(18_000L);
    UUID billId = openBillWithLine(itemId, 2); // 36,000

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                billService.initiatePendingPayment(
                    billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null)));

    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            pending.paymentId(),
            BUSINESS,
            pending.amountMinor(),
            "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(pending.paymentId())).isEqualTo("CAPTURED"));

    assertThat(billStatusAsAdmin(billId)).isEqualTo("PAID");
    assertThat(saleIdForPaymentAsAdmin(pending.paymentId())).isNotNull();
    assertThat(pendingReservationCountAsAdmin(billId)).isZero();
  }

  /**
   * W3 (code review test gap): a mismatched amount/currency for a BILL payment PARKS — exercised
   * independently of the shared order-path test ({@link
   * PaymentChargeSucceededConsumeAcceptanceTest#amountMismatchParksAndLeavesThePaymentPending()}),
   * since the review explicitly does not want the bill path's park behaviour to rely on that being
   * representative. The mismatch check runs BEFORE the order-vs-bill dispatch split, but this
   * proves a bill-originated payment reaches it and parks correctly rather than, say, NPEing on a
   * null {@code orderId} somewhere in the shared pre-dispatch code.
   */
  @Test
  void mismatchedAmountForABillPaymentParksAndLeavesThePaymentPending() throws Exception {
    UUID itemId = seedItem(30_000L);
    UUID billId = openBillWithLine(itemId, 1); // 30,000

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                billService.initiatePendingPayment(
                    billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null)));

    // The webhook reports a DIFFERENT amount than what was authorized at pay-pending mint time.
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            pending.paymentId(),
            BUSINESS,
            pending.amountMinor() + 1L,
            "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(errorLogCountAsAdmin("restaurant.payment-charge.amount-mismatch"))
                    .isEqualTo(1L));

    // Parked, not captured: the payment stays PENDING, the bill stays OPEN, the reservation holds,
    // and no sale was recorded.
    assertThat(paymentStatusAsAdmin(pending.paymentId())).isEqualTo("PENDING");
    assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
    assertThat(pendingReservationCountAsAdmin(billId)).isEqualTo(1L);
  }

  @Test
  void settledChargeForAnOrderPaymentStillRoutesToOrderCapture() throws Exception {
    UUID itemId = seedItem(21_000L);
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "bill-dispatch-order-regression-001",
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.QRIS, null))));
    UUID orderPaymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "restaurant", orderPaymentId, BUSINESS, amount, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(orderPaymentId)).isEqualTo("CAPTURED"));

    String[] orderState = orderStateAsAdmin(checkout.order().orderId());
    assertThat(orderState[0]).as("order status must be COMPLETED").isEqualTo("COMPLETED");
    assertThat(orderState[1]).as("order must have sale_id after capture").isNotNull();
  }

  // ---------------------------------------------------------------- helpers

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String paymentStatusAsAdmin(UUID paymentId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT status FROM payment WHERE id = ?")) {
      ps.setObject(1, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("payment row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  private String billStatusAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT status FROM bill WHERE id = ?")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("bill row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  private UUID saleIdForPaymentAsAdmin(UUID paymentId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT sale_id FROM payment WHERE id = ?")) {
      ps.setObject(1, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("payment row must exist").isTrue();
        return (UUID) rs.getObject("sale_id");
      }
    }
  }

  private long pendingReservationCountAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM bill_line WHERE bill_id = ? AND pending_payment_id IS NOT"
                    + " NULL")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long errorLogCountAsAdmin(String source) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM error_log WHERE source = ?")) {
      ps.setString(1, source);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String[] orderStateAsAdmin(UUID orderId) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status, sale_id FROM restaurant_order WHERE id = '" + orderId + "'")) {
      rs.next();
      return new String[] {rs.getString("status"), rs.getString("sale_id")};
    }
  }
}
