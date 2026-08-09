package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.EventFixtures;
import id.co.nativeapp.restaurant.KafkaPostgresRedisTestBase;
import id.co.nativeapp.restaurant.config.ActorTypeProvider;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.restaurant.sale.domain.OperatorMismatchException;
import id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException;
import id.co.nativeapp.security.OperatorPrincipal;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code PaymentChargeSucceeded} (ADR 0045) driven end-to-end through Kafka — restaurant-service's
 * consumer of payment-service's dynamic-QRIS gateway settlement. Mirrors {@code
 * entitlement.EntitlementGateConsumeTest} in shape (real Kafka + Postgres via {@link
 * KafkaPostgresRedisTestBase}) and finance-service's {@code SaleRecordedIdempotencyTest} marker
 * technique for a race-free no-double-capture proof.
 */
@SpringBootTest
class PaymentChargeSucceededConsumeAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "33333333-3333-3333-3333-333333333333";
  private static final String ACTOR = "cashier-charge@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService captureService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, "Kopi", "DRINK", priceMinor, "IDR"))
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

  /**
   * ADR 0049 P4: runs the SAME QRIS checkout as {@link #checkoutQris} but with an {@code
   * X-Actor-Type: device} request bound (mirroring the gateway-injected header) and, when {@code
   * operatorUserId} is non-null, a pre-verified {@link OperatorPrincipal} request attribute
   * (mirroring what {@code OperatorSessionFilter} sets after verifying a real {@code
   * X-Operator-Session} token). Always resets the request context afterward, so the subsequent
   * (real, Kafka-driven) capture never sees a stray device header.
   */
  private CheckoutResult checkoutQrisAsDevice(
      String idempotencyKey, UUID itemId, String operatorUserId) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorTypeProvider.ACTOR_TYPE_HEADER, ActorTypeProvider.DEVICE);
    if (operatorUserId != null) {
      request.setAttribute(
          OperatorPrincipal.REQUEST_ATTRIBUTE,
          new OperatorPrincipal(TENANT, BUSINESS, operatorUserId, UUID.randomUUID(), "cashier"));
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
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
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  void settledChargeCapturesThePendingPaymentAndRedeliveryDoesNotDoubleCapture() throws Exception {
    UUID itemId = seedItem(20_000L);

    // The payment under test, and a second (marker) payment used to prove the duplicate delivery
    // has drained through the single-threaded per-key consumer before we assert (mirrors
    // finance-service's SaleRecordedIdempotencyTest marker technique).
    CheckoutResult checkout1 = checkoutQris("charge-consume-001", itemId);
    UUID payment1 = checkout1.order().payment().paymentId();
    long amount1 = checkout1.order().payment().amountMinor();

    CheckoutResult checkout2 = checkoutQris("charge-consume-002", itemId);
    UUID payment2 = checkout2.order().payment().paymentId();
    long amount2 = checkout2.order().payment().amountMinor();

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId, TENANT, "restaurant", payment1, BUSINESS, amount1, "IDR");

    // Deliver the SAME event id twice (at-least-once redelivery of one logical event).
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, eventId, event);
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, eventId, event);

    // A distinct marker event (a different charge/payment) published after the duplicate; once its
    // capture has posted, the duplicate for payment1 has necessarily already been drained (same
    // company_id key -> same partition -> single-threaded in-order consumption).
    GenericRecord marker =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "restaurant", payment2, BUSINESS, amount2, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    // Exactly two SaleRecorded rows total: payment1's single capture + the marker's capture. NOT
    // three (which a double-capture of payment1 would produce).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(saleRecordedCountAsAdmin()).isEqualTo(2L));

    assertThat(paymentStatusAsAdmin(payment1)).isEqualTo("CAPTURED");
    assertThat(paymentStatusAsAdmin(payment2)).isEqualTo("CAPTURED");
    assertThat(processedEventCountAsAdmin(eventId))
        .as("the durable event id is claimed exactly once")
        .isEqualTo(1L);
  }

  @Test
  void chargeForAnotherVerticalIsSkipped() throws Exception {
    UUID itemId = seedItem(15_000L);
    CheckoutResult checkout = checkoutQris("charge-consume-vertical-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // Mislabelled vertical — not ours; must be skipped (never captured), even though paymentId
    // and amount would otherwise match.
    GenericRecord carwashEvent =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "carwash", paymentId, BUSINESS, amount, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), carwashEvent);

    // A distinct restaurant-vertical marker on a second payment proves the carwash event has
    // already drained (same partition key) before the PENDING assertion below.
    UUID itemId2 = seedItem(15_000L);
    CheckoutResult markerCheckout = checkoutQris("charge-consume-vertical-002", itemId2);
    UUID markerPaymentId = markerCheckout.order().payment().paymentId();
    long markerAmount = markerCheckout.order().payment().amountMinor();
    GenericRecord marker =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            markerPaymentId,
            BUSINESS,
            markerAmount,
            "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(paymentStatusAsAdmin(markerPaymentId)).isEqualTo("CAPTURED"));

    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("PENDING");
  }

  @Test
  void amountMismatchParksAndLeavesThePaymentPending() throws Exception {
    UUID itemId = seedItem(30_000L);
    CheckoutResult checkout = checkoutQris("charge-consume-mismatch-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "restaurant", paymentId, BUSINESS, amount + 1L, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(errorLogCountAsAdmin("restaurant.payment-charge.amount-mismatch"))
                    .isEqualTo(1L));

    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("PENDING");
  }

  @Test
  void chargeForAnAlreadyCapturedPaymentNoOps() throws Exception {
    UUID itemId = seedItem(18_000L);
    CheckoutResult checkout = checkoutQris("charge-consume-alreadycaptured-001", itemId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // Simulate the cashier's manual "mark as paid" winning before the webhook arrives.
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);

    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "restaurant", paymentId, BUSINESS, amount, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    // A distinct marker payment proves the already-captured event has drained before we assert no
    // second SaleRecorded was emitted for paymentId.
    UUID itemId2 = seedItem(18_000L);
    CheckoutResult markerCheckout = checkoutQris("charge-consume-alreadycaptured-002", itemId2);
    UUID markerPaymentId = markerCheckout.order().payment().paymentId();
    long markerAmount = markerCheckout.order().payment().amountMinor();
    GenericRecord marker =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(),
            TENANT,
            "restaurant",
            markerPaymentId,
            BUSINESS,
            markerAmount,
            "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(saleRecordedCountAsAdmin()).isEqualTo(2L));

    assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("CAPTURED");
  }

  // ---------------------------------------------------------------- ADR 0049 P4: device guard +
  // async operator threading

  /**
   * A device (outlet-terminal) actor with NO verified operator session is rejected at CHECKOUT —
   * the synchronous step that mints the PENDING digital payment — before it ever reaches Kafka.
   */
  @Test
  void aDeviceDigitalSaleWithNoOperatorIsRejectedAtCheckout() throws Exception {
    UUID itemId = seedItem(20_000L);

    assertThatThrownBy(() -> checkoutQrisAsDevice("device-no-operator-checkout-001", itemId, null))
        .isInstanceOf(OperatorRequiredException.class);
  }

  /**
   * The load-bearing ADR 0049 P4 proof: a device digital sale rung WITH a verified operator session
   * is enforced (admitted) at checkout, and the operator captured THEN is threaded through to the
   * async {@code PaymentChargeSucceeded} capture — the sale's {@code sold_by_user_id} and the
   * {@code MetricPublished} commission subject both credit the OPERATOR, never the bound (device)
   * actor, even though the capture itself runs with no request context at all (a real Kafka
   * consumer thread).
   */
  @Test
  void aDeviceDigitalSaleWithAnOperatorCreditsTheOperatorAtAsyncCapture() throws Exception {
    UUID itemId = seedItem(22_000L);
    String operatorUserId = UUID.randomUUID().toString();

    CheckoutResult checkout =
        checkoutQrisAsDevice("device-operator-checkout-001", itemId, operatorUserId);
    UUID paymentId = checkout.order().payment().paymentId();
    long amount = checkout.order().payment().amountMinor();

    // The ring-time operator was already stamped onto the PENDING payment at checkout.
    assertThat(soldByUserIdOnPaymentAsAdmin(paymentId)).isEqualTo(operatorUserId);

    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            UUID.randomUUID(), TENANT, "restaurant", paymentId, BUSINESS, amount, "IDR");
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), TENANT, UUID.randomUUID(), event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(paymentStatusAsAdmin(paymentId)).isEqualTo("CAPTURED"));

    UUID saleId = saleIdForPaymentAsAdmin(paymentId);
    assertThat(saleId).isNotNull();
    assertThat(soldByUserIdOnSaleAsAdmin(saleId)).isEqualTo(operatorUserId);

    GenericRecord metric = metricPublishedForSaleAsAdmin(saleId);
    assertThat(metric.get("subject_id").toString()).isEqualTo(operatorUserId);
  }

  /**
   * ADR 0049 P4 security fix: a device digital checkout presenting a validly-shaped operator
   * session bound to a DIFFERENT outlet is rejected at the PENDING-mint choke point ({@link
   * OperatorMismatchException}) — the SAME tenant/outlet assertion the synchronous cash path
   * enforces now also guards the digital-tender stamp, so a stolen/misdirected but validly-signed
   * token can never become the stored ring-time seller that async capture would otherwise trust
   * as-is. The checkout transaction rolls back, so no PENDING payment is ever minted.
   */
  @Test
  void aDeviceDigitalSaleWithAForeignOutletOperatorIsRejectedAtCheckout() throws Exception {
    UUID itemId = seedItem(20_000L);
    UUID foreignOutlet = UUID.fromString("55555555-5555-5555-5555-555555555555");

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorTypeProvider.ACTOR_TYPE_HEADER, ActorTypeProvider.DEVICE);
    request.setAttribute(
        OperatorPrincipal.REQUEST_ATTRIBUTE,
        new OperatorPrincipal(
            TENANT, foreignOutlet, UUID.randomUUID().toString(), UUID.randomUUID(), "cashier"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      assertThatThrownBy(
              () ->
                  TenantContext.callAs(
                      TENANT,
                      ACTOR,
                      () ->
                          orderService.checkout(
                              new CheckoutRequest(
                                  BUSINESS,
                                  "device-foreign-outlet-checkout-001",
                                  List.of(new OrderLineRequest(itemId, 1)),
                                  new PaymentRequest(TenderType.QRIS, null)))))
          .isInstanceOf(OperatorMismatchException.class);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  /**
   * ADR 0049 P4 (code-review follow-up): commission credit follows the RING-TIME operator, not
   * whoever holds a live session at CAPTURE. A device digital sale rung by operator R and then
   * MANUALLY captured on a live device request by a DIFFERENT operator C credits R — the stored,
   * already-validated ring-time seller wins over the live capturer, so a shift change can never
   * move commission away from the operator who actually rang it.
   */
  @Test
  void aManualCaptureByADifferentOperatorStillCreditsTheRingTimeOperator() throws Exception {
    UUID itemId = seedItem(24_000L);
    String ringer = UUID.randomUUID().toString();
    String capturer = UUID.randomUUID().toString();

    CheckoutResult checkout = checkoutQrisAsDevice("device-ring-then-capture-001", itemId, ringer);
    UUID paymentId = checkout.order().payment().paymentId();
    assertThat(soldByUserIdOnPaymentAsAdmin(paymentId)).isEqualTo(ringer);

    // A DIFFERENT operator marks the payment paid on a live device request (shift change).
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorTypeProvider.ACTOR_TYPE_HEADER, ActorTypeProvider.DEVICE);
    request.setAttribute(
        OperatorPrincipal.REQUEST_ATTRIBUTE,
        new OperatorPrincipal(TENANT, BUSINESS, capturer, UUID.randomUUID(), "cashier"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    UUID saleId = saleIdForPaymentAsAdmin(paymentId);
    assertThat(saleId).isNotNull();
    assertThat(soldByUserIdOnSaleAsAdmin(saleId)).isEqualTo(ringer);
    GenericRecord metric = metricPublishedForSaleAsAdmin(saleId);
    assertThat(metric.get("subject_id").toString()).isEqualTo(ringer);
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
        return rs.getString("status");
      }
    }
  }

  private long saleRecordedCountAsAdmin() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'")) {
      rs.next();
      return rs.getLong(1);
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

  /** ADR 0049 P4: the ring-time operator stamped onto a PENDING payment at checkout, if any. */
  private String soldByUserIdOnPaymentAsAdmin(UUID paymentId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT sold_by_user_id FROM payment WHERE id = ?")) {
      ps.setObject(1, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("payment row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  /** The sale id a captured payment linked, once {@code capture} has run. */
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

  /** ADR 0049 P4: {@code sale.sold_by_user_id} threaded through from the async capture. */
  private String soldByUserIdOnSaleAsAdmin(UUID saleId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT sold_by_user_id FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("sale row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  /** The {@code MetricPublished} (sales_amount @ employee) outbox row emitted for a sale. */
  private GenericRecord metricPublishedForSaleAsAdmin(UUID saleId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'MetricPublished' AND aggregate_id"
                    + " = ?")) {
      ps.setString(1, saleId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("MetricPublished outbox row must exist").isTrue();
        byte[] payload = rs.getBytes(1);
        return AvroSerde.deserialize(payload, MetricPublishedSchema.schema());
      }
    }
  }
}
