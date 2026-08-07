package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.dto.TicketPaymentResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
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
 * id.co.nativeapp.barbershop.payment.messaging.PaymentChargeSucceededListener} (ADR 0045), driven
 * end-to-end through a REAL Kafka broker. Ported from carwash-service's {@code
 * PaymentChargeSucceededConsumeAcceptanceTest} — the domain differences from {@code
 * CheckoutRequest} (ADR 0024: {@code chair} replaces {@code bay}, {@code staffProfileId} is
 * MANDATORY, {@code ItemType.SERVICE} replaces {@code PACKAGE}) are the only adjustments.
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
  private static final String BARBERSHOP = "barbershop";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void truncateErrorLog() throws Exception {
    // error_log is deliberately NOT truncated by PostgresRlsTestBase#resetTables (ADR 0005) —
    // truncate it ourselves so each test starts from an empty inbox (mirrors finance-service's
    // SealedPeriodQuarantineTest / carwash-service's own copy of this test).
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE error_log");
    }
  }

  @Test
  void settledChargeCapturesAPendingDigitalTicketExactlyOnceAcrossRedelivery() throws Exception {
    grantBarbershop(TENANT_A);
    CatalogItemResponse svc = createService("Haircut", 40_000_00L);
    StaffProfileResponse barber = createStaffProfile("Budi");
    CheckoutResult pending = digitalCheckout(svc, barber, "charge-redelivery-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();
    assertThat(payment.status()).isEqualTo("PENDING");

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "barbershop",
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
    grantBarbershop(TENANT_A);
    CatalogItemResponse svc = createService("Haircut", 30_000_00L);
    StaffProfileResponse barber = createStaffProfile("Budi");
    CheckoutResult pending = digitalCheckout(svc, barber, "charge-wrong-vertical-1");
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
    grantBarbershop(TENANT_A);
    CatalogItemResponse svc = createService("Haircut", 25_000_00L);
    StaffProfileResponse barber = createStaffProfile("Budi");
    CheckoutResult pending = digitalCheckout(svc, barber, "charge-amount-mismatch-1");
    UUID ticketId = pending.ticket().ticketId();
    TicketPaymentResponse payment = pending.ticket().payment();

    UUID chargeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    long wrongAmountMinor = payment.amountMinor() + 1_00L;
    GenericRecord event =
        EventFixtures.paymentChargeSucceeded(
            chargeId,
            TENANT_A,
            "barbershop",
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
    assertThat(errorLogCountForSource("barbershop.payment-charge.amount-mismatch")).isEqualTo(1L);
  }

  @Test
  void alreadyCapturedPaymentNoOpsOnASettledCharge() throws Exception {
    grantBarbershop(TENANT_A);
    CatalogItemResponse svc = createService("Haircut", 20_000_00L);
    StaffProfileResponse barber = createStaffProfile("Budi");
    CheckoutResult pending = digitalCheckout(svc, barber, "charge-already-captured-1");
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
            "barbershop",
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

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, BARBERSHOP, true));
  }

  private CatalogItemResponse createService(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createService(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR", null)));
  }

  private StaffProfileResponse createStaffProfile(String label) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createStaffProfile(
                new StaffProfileCreateRequest(OUTLET, label, null, true)));
  }

  private CheckoutResult digitalCheckout(
      CatalogItemResponse svc, StaffProfileResponse barber, String idempotencyKey)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            ticketService.checkout(
                new CheckoutRequest(
                    OUTLET,
                    idempotencyKey,
                    "chair-1",
                    barber.id(),
                    null,
                    List.of(new TicketLineInput(ItemType.SERVICE, svc.id(), 1)),
                    new PaymentRequest(TenderType.QRIS, null))));
  }

  // -------------------------------------------------------------------------
  // Admin helpers: direct DB queries bypassing RLS (BYPASSRLS superuser conn)
  // -------------------------------------------------------------------------

  private String paymentStatusAsAdmin(UUID ticketId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT status FROM barbershop_payment WHERE ticket_id = ?::uuid")) {
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
