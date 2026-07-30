package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.loyaltyref.domain.GiftCardUnusableException;
import id.co.nativeapp.carwash.loyaltyref.domain.LoyaltyBalanceInsufficientException;
import id.co.nativeapp.carwash.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.carwash.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.carwash.loyaltyref.service.GiftCardStateChangedService;
import id.co.nativeapp.carwash.loyaltyref.service.LoyaltyBalanceChangedService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.dto.TicketPaymentResponse;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 (ADR 0027) — loyalty-points and gift-card redemption over the real carwash ticket
 * checkout/capture flow, end to end. Ported from restaurant-service's {@code
 * LoyaltyGiftCardCheckoutIntegrationTest}, adapted to the ticket flow (checkout collapses straight
 * to a sale — no separate park/pay step, ADR 0023).
 *
 * <p>Covers: (1) points redemption — extended identity, the {@code carwash_ticket} redemption
 * columns, the decoded {@code SaleRecorded} wire shape (PROMO-ONLY {@code discount_minor}), and the
 * {@code member_balance_ref} decrement; (2) gift-card redemption — partial (residual cash tender)
 * and full (zero-amount CAPTURED payment, {@code tender_type = null}); (3) failure modes that write
 * NOTHING (unknown member, unknown/inactive gift card); (4) idempotent replay — no second
 * decrement, no second event; (5) the digital-tender capture path — {@code
 * SaleRecorded.amount_minor} carries the ticket's GRAND TOTAL, never the tender residual, and every
 * redemption ref is decremented exactly ONCE overall.
 *
 * <p>Race-based "insufficient balance" 409s are covered separately in {@link
 * LoyaltyGiftCardRedemptionConcurrencyTest} — see that class's javadoc for why a KNOWN member/card
 * with a lower balance is CLAMPED, not rejected.
 */
@SpringBootTest
class LoyaltyGiftCardCheckoutIntegrationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666601";
  private static final String ACTOR = "cashier-lg@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666602");

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private LoyaltyBalanceChangedService loyaltyBalanceChangedService;
  @Autowired private GiftCardStateChangedService giftCardStateChangedService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // -----------------------------------------------------------------------
  // 1. Points redemption
  // -----------------------------------------------------------------------

  @Test
  void pointsRedemptionHoldsExtendedIdentityPersistsColumnsAndDecrementsRefExactlyOnce()
      throws Exception {
    grantCarwash();
    UUID memberId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    UUID packageId = createPackage(10_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "points-" + UUID.randomUUID(),
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 8_000L),
            null,
            memberId,
            2_000L,
            null,
            null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));

    assertThat(result.created()).isTrue();
    TicketResponse ticket = result.ticket();
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(8_000L);
    assertThat(ticket.breakdown().loyaltyRedeemedMinor()).isEqualTo(2_000L);
    assertThat(ticket.breakdown().discountMinor()).as("promo-only, excludes loyalty").isZero();

    var bd = ticket.breakdown();
    assertThat(
            bd.subtotalMinor()
                - bd.discountMinor()
                - bd.loyaltyRedeemedMinor()
                + bd.serviceChargeMinor()
                + bd.taxMinor())
        .isEqualTo(bd.grandTotalMinor())
        .isEqualTo(8_000L);

    Map<String, Object> row = readTicketRedemptionColumnsAdmin(ticket.ticketId());
    assertThat(row.get("loyalty_member_id")).hasToString(memberId.toString());
    assertThat(((Number) row.get("loyalty_redeemed_points")).longValue()).isEqualTo(2_000L);
    assertThat(((Number) row.get("loyalty_redeemed_minor")).longValue()).isEqualTo(2_000L);
    assertThat(row.get("gift_card_id")).isNull();
    assertThat(row.get("gift_card_redeemed_minor")).isNull();

    GenericRecord decoded = readSaleRecordedAdmin(ticket.ticketId());
    assertThat(decoded.get("amount_minor")).isEqualTo(8_000L);
    assertThat(decoded.get("discount_minor")).as("PROMO-ONLY on the wire").isEqualTo(0L);
    assertThat(decoded.get("loyalty_member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(2_000L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(2_000L);

    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(1_000L);
  }

  // -----------------------------------------------------------------------
  // 2. Gift-card redemption — partial and full
  // -----------------------------------------------------------------------

  @Test
  void giftCardPartialRedemptionSettlesResidualAsCashAndDecrementsRefExactlyOnce()
      throws Exception {
    grantCarwash();
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID packageId = createPackage(50_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "giftcard-partial-" + UUID.randomUUID(),
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 30_000L),
            null,
            null,
            null,
            cardId,
            20_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));

    assertThat(result.created()).isTrue();
    TicketResponse ticket = result.ticket();
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(50_000L);
    TicketPaymentResponse payment = ticket.payment();
    assertThat(payment.amountMinor()).as("the residual, not the grand total").isEqualTo(30_000L);
    assertThat(payment.tenderedMinor()).isEqualTo(30_000L);
    assertThat(payment.changeMinor()).isZero();

    GenericRecord decoded = readSaleRecordedAdmin(ticket.ticketId());
    assertThat(decoded.get("amount_minor")).isEqualTo(50_000L);
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(20_000L);

    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);
  }

  @Test
  void giftCardFullCoverageRecordsZeroAmountCapturedPaymentWithNullTenderType() throws Exception {
    grantCarwash();
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 50_000L, "IDR", 1L);
    UUID packageId = createPackage(50_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "giftcard-full-" + UUID.randomUUID(),
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 0L),
            null,
            null,
            null,
            cardId,
            50_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));

    assertThat(result.created()).isTrue();
    TicketResponse ticket = result.ticket();
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(50_000L);
    TicketPaymentResponse payment = ticket.payment();
    assertThat(payment.amountMinor()).isZero();
    assertThat(payment.status()).isEqualTo("CAPTURED");

    GenericRecord decoded = readSaleRecordedAdmin(ticket.ticketId());
    assertThat(decoded.get("amount_minor")).isEqualTo(50_000L);
    assertThat(decoded.get("tender_type")).as("gift card fully covered the sale").isNull();
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(50_000L);

    assertThat(readGiftCardBalanceAdmin(cardId)).isZero();
  }

  // -----------------------------------------------------------------------
  // 3. Failure modes — 409, nothing written
  // -----------------------------------------------------------------------

  @Test
  void unknownLoyaltyMemberRejectsCheckoutAndWritesNothing() throws Exception {
    grantCarwash();
    UUID unknownMember = UUID.randomUUID();
    UUID packageId = createPackage(10_000L);
    String idemKey = "unknown-member-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            unknownMember,
            500L,
            null,
            null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request)))
        .isInstanceOf(LoyaltyBalanceInsufficientException.class);

    assertThat(countTicketsAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  @Test
  void unknownGiftCardRejectsCheckoutAndWritesNothing() throws Exception {
    grantCarwash();
    UUID unknownCard = UUID.randomUUID();
    UUID packageId = createPackage(10_000L);
    String idemKey = "unknown-card-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            null,
            null,
            unknownCard,
            5_000L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request)))
        .isInstanceOf(GiftCardUnusableException.class);

    assertThat(countTicketsAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  @Test
  void depletedGiftCardRejectsCheckoutAndWritesNothing() throws Exception {
    grantCarwash();
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "DEPLETED", 0L, "IDR", 1L);
    UUID packageId = createPackage(10_000L);
    String idemKey = "depleted-card-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            null,
            null,
            cardId,
            5_000L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request)))
        .isInstanceOf(GiftCardUnusableException.class);

    assertThat(countTicketsAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  // -----------------------------------------------------------------------
  // 4. Idempotent replay
  // -----------------------------------------------------------------------

  @Test
  void idempotentReplayDoesNotDoubleDecrementOrEmitASecondEvent() throws Exception {
    grantCarwash();
    UUID memberId = UUID.randomUUID();
    UUID cardId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID packageId = createPackage(50_000L);
    String idemKey = "replay-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 44_000L),
            null,
            memberId,
            1_000L,
            cardId,
            5_000L);

    CheckoutResult first = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));
    assertThat(first.created()).isTrue();
    UUID ticketId = first.ticket().ticketId();

    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(25_000L);
    assertThat(countSaleRecordedEventsAdmin()).isEqualTo(1);

    CheckoutResult replay = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));

    assertThat(replay.created()).isFalse();
    assertThat(replay.ticket().ticketId()).isEqualTo(ticketId);
    assertThat(readMemberBalanceAdmin(memberId)).as("no second decrement").isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).as("no second decrement").isEqualTo(25_000L);
    assertThat(countSaleRecordedEventsAdmin()).as("no second event").isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // 5. Digital-tender capture path
  // -----------------------------------------------------------------------

  @Test
  void digitalCaptureEmitsSaleRecordedWithTheTicketGrandTotalNotTheResidual() throws Exception {
    grantCarwash();
    UUID memberId = UUID.randomUUID();
    UUID cardId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID packageId = createPackage(100_000L);
    String idemKey = "digital-capture-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "bay-2",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.QRIS, null),
            null,
            memberId,
            1_000L,
            cardId,
            20_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));
    assertThat(result.created()).isTrue();
    TicketResponse ticket = result.ticket();
    // grandTotal = 100,000 - 1,000 (loyalty) = 99,000; residual = 99,000 - 20,000 (gift card) =
    // 79,000; digital + residual > 0 -> PENDING, no sale yet.
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(99_000L);
    assertThat(ticket.saleId()).isNull();
    TicketPaymentResponse pending = ticket.payment();
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.amountMinor()).isEqualTo(79_000L);

    // Redemption already applied at CHECKOUT time — before any capture.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);

    TicketResponse captured =
        TenantContext.callAs(TENANT, ACTOR, () -> ticketService.capture(ticket.ticketId()));
    assertThat(captured.payment().status()).isEqualTo("CAPTURED");
    assertThat(captured.saleId()).isNotNull();

    GenericRecord decoded = readSaleRecordedAdmin(captured.ticketId());
    // Under test: amount_minor MUST be the ticket's GRAND TOTAL (99,000), never the 79,000
    // residual.
    assertThat(decoded.get("amount_minor")).as("ticket grand total, NOT the residual").isEqualTo(99_000L);
    assertThat(decoded.get("loyalty_member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(1_000L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(1_000L);
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(20_000L);
    assertThat(decoded.get("discount_minor")).as("promo-only, excludes loyalty").isEqualTo(0L);

    // Refs decremented exactly ONCE overall.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);
  }

  // -----------------------------------------------------------------------
  // Fixtures / helpers
  // -----------------------------------------------------------------------

  private void grantCarwash() {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, "carwash", true));
  }

  private UUID createPackage(long priceMinor) throws Exception {
    return TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(
                        OUTLET, "Wash " + UUID.randomUUID(), null, priceMinor, "IDR")))
        .id();
  }

  private void seedMemberBalance(UUID memberId, long points, long seq) {
    loyaltyBalanceChangedService.apply(
        new LoyaltyBalanceChangedEvent(UUID.randomUUID(), memberId, TENANT, points, seq, Instant.now()));
  }

  private void seedGiftCard(UUID cardId, String state, long balanceMinor, String currency, long seq) {
    giftCardStateChangedService.apply(
        new GiftCardStateChangedEvent(
            UUID.randomUUID(), cardId, TENANT, state, balanceMinor, currency, seq, Instant.now()));
  }

  private long readMemberBalanceAdmin(UUID memberId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT points_balance FROM member_balance_ref WHERE member_id = ?")) {
      ps.setObject(1, memberId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long readGiftCardBalanceAdmin(UUID cardId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT balance_minor FROM gift_card_ref WHERE gift_card_id = ?")) {
      ps.setObject(1, cardId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Map<String, Object> readTicketRedemptionColumnsAdmin(UUID ticketId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor,"
                    + " gift_card_id, gift_card_redeemed_minor FROM carwash_ticket WHERE id = ?")) {
      ps.setObject(1, ticketId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("loyalty_member_id", rs.getObject("loyalty_member_id"));
        row.put("loyalty_redeemed_points", rs.getObject("loyalty_redeemed_points"));
        row.put("loyalty_redeemed_minor", rs.getObject("loyalty_redeemed_minor"));
        row.put("gift_card_id", rs.getObject("gift_card_id"));
        row.put("gift_card_redeemed_minor", rs.getObject("gift_card_redeemed_minor"));
        return row;
      }
    }
  }

  private int countTicketsAdmin(String idempotencyKey) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM carwash_ticket WHERE idempotency_key = ?")) {
      ps.setString(1, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countSaleRecordedEventsAdmin() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM outbox WHERE event_type = 'SaleRecorded'")) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private GenericRecord readSaleRecordedAdmin(UUID ticketId) {
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'SaleRecorded' AND aggregate_id = ?"
                + " ORDER BY occurred_at DESC LIMIT 1",
            ticketId.toString());
    return AvroSerde.deserialize(
        (byte[]) outboxRow.get("payload"),
        id.co.nativeapp.carwash.ticket.messaging.TicketSaleRecordedSchema.schema());
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
