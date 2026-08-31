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
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code PaymentChargeExpired} (ADR 0045) driven end-to-end through Kafka — restaurant-service's
 * consumer of a dynamic-QRIS gateway charge that terminated WITHOUT settling. The un-happy-path
 * counterpart of {@link PaymentChargeSucceededConsumeAcceptanceTest} / {@link
 * BillPaymentChargeSucceededTest}: it proves the release, not a capture. Real Kafka + Postgres via
 * {@link KafkaPostgresRedisTestBase}; the distinct-marker technique proves a same-partition delivery
 * has drained before each PENDING/CAPTURED assertion.
 */
@SpringBootTest
class PaymentChargeExpiredConsumeAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "ec000001-ec00-ec00-ec00-ec0000000001";
  private static final String ACTOR = "cashier-expired@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("ec000001-ec00-ec00-ec00-ec0000000002");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private BillService billService;
  @Autowired private PaymentCaptureService captureService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, "Teh", "DRINK", priceMinor, "IDR"))
                .id());
  }

  private CheckoutResult checkoutQris(String idempotencyKey, UUID itemId) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS,
                    idempotencyKey,
                    List.of(new OrderLineRequest(itemId, 1)),
                    new PaymentRequest(TenderType.QRIS, null))));
  }

  private UUID openBillWithLine(UUID itemId, int qty) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, null, "Expired Guest")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, qty)))));
    return opened.id();
  }

  @Test
  void expiredChargeForAnOrderPaymentRevertsTheOrderAndRedeliveryIsIdempotent() throws Exception {
    UUID itemId = seedItem(20_000L);

    CheckoutResult checkout1 = checkoutQris("expired-order-001", itemId);
    UUID payment1 = checkout1.order().payment().paymentId();
    long amount1 = checkout1.order().payment().amountMinor();
    assertThat(paymentStatusAsAdmin(payment1)).isEqualTo("PENDING");
    assertThat(orderStatusAsAdmin(checkout1.order().orderId())).isEqualTo("AWAITING_PAYMENT");

    // A second checkout used only as the drain marker for the duplicate below.
    CheckoutResult checkout2 = checkoutQris("expired-order-002", itemId);
    UUID payment2 = checkout2.order().payment().paymentId();
    long amount2 = checkout2.order().payment().amountMinor();

    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(), TENANT, "restaurant", payment1, BUSINESS, amount1, "IDR", "EXPIRED");
    // At-least-once redelivery of ONE logical event (same event id twice).
    EventFixtures.publishPaymentChargeExpired(KAFKA.getBootstrapServers(), TENANT, eventId, event);
    EventFixtures.publishPaymentChargeExpired(KAFKA.getBootstrapServers(), TENANT, eventId, event);

    // A distinct marker on payment2, published after the duplicate — once it releases, the duplicate
    // for payment1 has necessarily drained (same company_id key → same partition → in order).
    GenericRecord marker =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(), TENANT, "restaurant", payment2, BUSINESS, amount2, "IDR", "CANCELED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(paymentStatusAsAdmin(payment2)).isEqualTo("ABANDONED"));

    // payment1 released exactly once: ABANDONED, its order reverted to PENDING (payable again).
    assertThat(paymentStatusAsAdmin(payment1)).isEqualTo("ABANDONED");
    assertThat(orderStatusAsAdmin(checkout1.order().orderId())).isEqualTo("PENDING");
    assertThat(processedEventCountAsAdmin(eventId))
        .as("the durable event id is claimed exactly once")
        .isEqualTo(1L);
  }

  @Test
  void expiredChargeForABillPaymentReleasesTheReservationAndAbandons() throws Exception {
    UUID itemId = seedItem(18_000L);
    UUID billId = openBillWithLine(itemId, 2); // 36,000

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                billService.initiatePendingPayment(
                    billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null)));
    assertThat(pendingReservationCountAsAdmin(billId)).isEqualTo(1L);

    GenericRecord event =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            pending.paymentId(),
            BUSINESS,
            pending.amountMinor(),
            "IDR",
            "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(pending.paymentId())).isEqualTo("ABANDONED"));

    // The reservation is released and the bill stays OPEN — it can be paid by cash / a fresh QR.
    assertThat(pendingReservationCountAsAdmin(billId)).isZero();
    assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
  }

  @Test
  void expiredChargeForAnAlreadyCapturedPaymentNoOps() throws Exception {
    UUID itemId = seedItem(22_000L);
    CheckoutResult checkout = checkoutQris("expired-captured-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // The cashier's manual mark-as-paid wins before the charge dies.
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("CAPTURED");

    GenericRecord event =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(), TENANT, "restaurant", paymentId, BUSINESS, amount, "IDR", "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    // Drain marker on a second payment.
    UUID itemId2 = seedItem(22_000L);
    CheckoutResult markerCheckout = checkoutQris("expired-captured-002", itemId2);
    UUID markerPaymentId = markerCheckout.order().payment().paymentId();
    long markerAmount = markerCheckout.order().payment().amountMinor();
    GenericRecord marker =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            markerPaymentId,
            BUSINESS,
            markerAmount,
            "IDR",
            "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(markerPaymentId)).isEqualTo("ABANDONED"));

    // The captured payment is untouched — its sale stands.
    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("CAPTURED");
    assertThat(orderStatusAsAdmin(checkout.order().orderId())).isEqualTo("COMPLETED");
  }

  @Test
  void expiredChargeForAnotherVerticalIsSkipped() throws Exception {
    UUID itemId = seedItem(15_000L);
    CheckoutResult checkout = checkoutQris("expired-vertical-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // Mislabelled vertical — not ours; must be skipped (never released).
    GenericRecord carwashEvent =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(), TENANT, "carwash", paymentId, BUSINESS, amount, "IDR", "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), carwashEvent);

    // Drain marker (a real restaurant expiry on a second payment).
    UUID itemId2 = seedItem(15_000L);
    CheckoutResult markerCheckout = checkoutQris("expired-vertical-002", itemId2);
    UUID markerPaymentId = markerCheckout.order().payment().paymentId();
    long markerAmount = markerCheckout.order().payment().amountMinor();
    GenericRecord marker =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            markerPaymentId,
            BUSINESS,
            markerAmount,
            "IDR",
            "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(markerPaymentId)).isEqualTo("ABANDONED"));

    // The mislabelled event left the real payment PENDING, its order still AWAITING_PAYMENT.
    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("PENDING");
    assertThat(orderStatusAsAdmin(checkout.order().orderId())).isEqualTo("AWAITING_PAYMENT");
  }

  @Test
  void expiredChargeForAPaymentWhoseOrderDivergedFromAwaitingPaymentParks() throws Exception {
    UUID itemId = seedItem(20_000L);
    CheckoutResult checkout = checkoutQris("expired-divergence-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // Force the divergence the RELEASE_FAILED branch guards against: the payment stays PENDING but
    // its order is moved OUT of AWAITING_PAYMENT (a state that normal flows keep in lock-step). The
    // release must NOT touch the payment — it parks for a human, since fresh payment status is still
    // PENDING (so this is a real divergence, not the benign capture-won race).
    updateOrderStatusAsAdmin(checkout.order().orderId(), "PENDING");

    GenericRecord event =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(), TENANT, "restaurant", paymentId, BUSINESS, amount, "IDR", "EXPIRED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        errorLogCountAsAdmin("restaurant.payment-charge-expired.release-failed"))
                    .isEqualTo(1L));

    // The tender is left untouched (PENDING) for the human to reconcile.
    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("PENDING");
  }

  @Test
  void expiredChargeForAnUnknownPaymentParks() throws Exception {
    UUID unknownPayment = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeExpired(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            unknownPayment,
            BUSINESS,
            50_000L,
            "IDR",
            "FAILED");
    EventFixtures.publishPaymentChargeExpired(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        errorLogCountAsAdmin("restaurant.payment-charge-expired.unknown-payment"))
                    .isEqualTo(1L));
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

  private String orderStatusAsAdmin(UUID orderId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT status FROM restaurant_order WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("order row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  private void updateOrderStatusAsAdmin(UUID orderId, String status) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE restaurant_order SET status = ? WHERE id = ?")) {
      ps.setString(1, status);
      ps.setObject(2, orderId);
      assertThat(ps.executeUpdate()).as("order row must be updated").isEqualTo(1);
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

  private long processedEventCountAsAdmin(UUID eventId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM processed_event WHERE event_id = ?")) {
      ps.setObject(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
