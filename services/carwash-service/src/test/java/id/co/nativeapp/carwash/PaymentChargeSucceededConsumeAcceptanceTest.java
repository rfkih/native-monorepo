package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.dto.TicketPaymentResponse;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consume acceptance + idempotency tests for {@link
 * id.co.nativeapp.carwash.payment.messaging.PaymentChargeSucceededListener} (ADR 0045), driven
 * end-to-end through a REAL Kafka broker (mirrors {@link LoyaltyRefKafkaConsumeTest}).
 *
 * <p>Covers the four scenarios from the payment-service integration task:
 *
 * <ol>
 *   <li>a settled charge captures a PENDING digital ticket's payment exactly once, even across
 *       redelivery of the SAME event id (one sale, one {@code SaleRecorded} outbox row);
 *   <li>a wrong-vertical event ({@code restaurant}) is skipped — the payment stays PENDING;
 *   <li>an amount mismatch parks (recorded to the error inbox) and the payment stays PENDING, never
 *       captured;
 *   <li>an already-CAPTURED payment (the cashier's manual mark-as-paid raced the webhook) no-ops —
 *       no second {@code SaleRecorded}.
 * </ol>
 */
@SpringBootTest
class PaymentChargeSucceededConsumeAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void truncateErrorLog() throws Exception {
    // error_log is deliberately NOT truncated by PostgresRlsTestBase#resetTables (ADR 0005) —
    // truncate it ourselves so each test starts from an empty inbox (mirrors finance-service's
    // SealedPeriodQuarantineTest).
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE error_log");
    }
  }

  @Test
  void settledChargeCapturesAPendingDigitalTicketExactlyOnceAcrossRedelivery() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 40_000_00L);
    CheckoutResult pending = digitalCheckout(pkg, "charge-redelivery-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();
    assertThat(payment.status()).isEqualTo("PENDING");

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "carwash",
            payment.paymentId(),
            ticketId,
            OUTLET,
            payment.amountMinor(),
            payment.currency(),
            "MIDTRANS",
            "mt-redelivery-1");

    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), chargeId, eventId, event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(paymentStatusAsAdmin(ticketId)).isEqualTo("CAPTURED"));
    assertThat(saleRecordedCount()).isEqualTo(1L);

    // Redelivery of the SAME event id must never double-capture / double-emit.
    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), chargeId, eventId, event);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(processedEventCount(eventId)).isEqualTo(1L));
    assertThat(saleRecordedCount()).isEqualTo(1L);
    assertThat(paymentStatusAsAdmin(ticketId)).isEqualTo("CAPTURED");
  }

  @Test
  void wrongVerticalEventIsSkippedAndLeavesThePaymentPending() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 30_000_00L);
    CheckoutResult pending = digitalCheckout(pkg, "charge-wrong-vertical-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "restaurant",
            payment.paymentId(),
            ticketId,
            OUTLET,
            payment.amountMinor(),
            payment.currency(),
            "MIDTRANS",
            "mt-wrong-vertical-1");

    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), chargeId, eventId, event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(processedEventCount(eventId)).isEqualTo(1L));
    assertThat(paymentStatusAsAdmin(ticketId)).isEqualTo("PENDING");
    assertThat(saleRecordedCount()).isZero();
  }

  @Test
  void amountMismatchParksAndLeavesThePaymentPending() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 25_000_00L);
    CheckoutResult pending = digitalCheckout(pkg, "charge-amount-mismatch-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    long wrongAmountMinor = payment.amountMinor() + 1_00L;
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "carwash",
            payment.paymentId(),
            ticketId,
            OUTLET,
            wrongAmountMinor,
            payment.currency(),
            "MIDTRANS",
            "mt-amount-mismatch-1");

    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), chargeId, eventId, event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(processedEventCount(eventId)).isEqualTo(1L));
    assertThat(paymentStatusAsAdmin(ticketId)).isEqualTo("PENDING");
    assertThat(saleRecordedCount()).isZero();
    assertThat(errorLogCountForSource("carwash.payment-charge.amount-mismatch")).isEqualTo(1L);
  }

  @Test
  void alreadyCapturedPaymentNoOpsOnASettledCharge() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 20_000_00L);
    CheckoutResult pending = digitalCheckout(pkg, "charge-already-captured-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();

    // The cashier's manual "mark as paid" captures it first — races the webhook.
    TicketResponse captured =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.capture(ticketId));
    assertThat(captured.payment().status()).isEqualTo("CAPTURED");
    assertThat(saleRecordedCount()).isEqualTo(1L);

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "carwash",
            payment.paymentId(),
            ticketId,
            OUTLET,
            payment.amountMinor(),
            payment.currency(),
            "MIDTRANS",
            "mt-already-captured-1");

    EventFixtures.publishPaymentChargeSucceeded(
        KAFKA.getBootstrapServers(), chargeId, eventId, event);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(processedEventCount(eventId)).isEqualTo(1L));
    assertThat(saleRecordedCount()).isEqualTo(1L);
    assertThat(paymentStatusAsAdmin(ticketId)).isEqualTo("CAPTURED");
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, CARWASH, true));
  }

  private CatalogItemResponse createPackage(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createPackage(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR")));
  }

  private CheckoutResult digitalCheckout(CatalogItemResponse pkg, String idempotencyKey)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            ticketService.checkout(
                new CheckoutRequest(
                    OUTLET,
                    idempotencyKey,
                    "bay-1",
                    null,
                    null,
                    null,
                    List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
                    new PaymentRequest(TenderType.QRIS, null))));
  }

  // -------------------------------------------------------------------------
  // Admin helpers: direct DB queries bypassing RLS (BYPASSRLS superuser conn)
  // -------------------------------------------------------------------------

  private String paymentStatusAsAdmin(UUID ticketId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT status FROM carwash_payment WHERE ticket_id = ?::uuid")) {
      ps.setString(1, ticketId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private long processedEventCount(UUID eventId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM processed_event WHERE event_id = ?::uuid")) {
      ps.setString(1, eventId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long errorLogCountForSource(String source) throws Exception {
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

  private long saleRecordedCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
