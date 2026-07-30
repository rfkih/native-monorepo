package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.loyaltyref.domain.GiftCardUnusableException;
import id.co.nativeapp.restaurant.loyaltyref.domain.LoyaltyBalanceInsufficientException;
import id.co.nativeapp.restaurant.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.restaurant.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.restaurant.loyaltyref.service.GiftCardStateChangedService;
import id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyBalanceChangedService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
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
 * Phase 4 (ADR 0027) — loyalty-points and gift-card redemption over the real restaurant order
 * checkout/park/pay-parked/capture flow, end to end.
 *
 * <p>A fresh tenant (no seeded tax/service-charge rule — see {@code PromotionCheckoutIntegrationTest}
 * precedent) so the extended reconciliation identity ({@code subtotal - discount - loyaltyRedeemed +
 * serviceCharge + tax == amount}, EVENT-CATALOG.md) is exact with zero tax/SC legs.
 *
 * <p>Covers: (1) points redemption — identity, the five persisted {@code restaurant_order} columns,
 * the decoded {@code SaleRecorded} wire shape (PROMO-ONLY {@code discount_minor}), and the {@code
 * member_balance_ref} decrement; (2) gift-card redemption — partial (residual cash tender) and full
 * (zero-amount CAPTURED payment, {@code tender_type = null}); (3) failure modes that write NOTHING
 * (unknown member, unknown/inactive gift card); (4) idempotent replay — no second decrement, no
 * second event; (5) the digital-tender capture path — {@code SaleRecorded.amount_minor} carries the
 * order's GRAND TOTAL, never the tender residual, and every redemption ref is decremented exactly
 * ONCE overall (at checkout, not again at capture); (6) a park/pay-parked case — redemption is
 * evaluated at PAY time, never at park time (mirrors the promotions posture, ADR 0026/0027).
 *
 * <p>Race-based "insufficient balance" 409s (the atomic decrement losing a concurrent race) are
 * covered separately in {@link LoyaltyGiftCardRedemptionConcurrencyTest} — by the guard's clamp
 * design (ADR 0027 decision 3), a KNOWN member/card with a balance merely lower than requested is
 * silently CLAMPED to the available amount, never rejected; the 409 path is reachable only via
 * "unknown to this cache" or a genuine concurrent-decrement loss.
 */
@SpringBootTest
class LoyaltyGiftCardCheckoutIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666601";
  private static final String ACTOR = "cashier-lg@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("66666666-6666-6666-6666-666666666602");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService paymentCaptureService;
  @Autowired private LoyaltyBalanceChangedService loyaltyBalanceChangedService;
  @Autowired private GiftCardStateChangedService giftCardStateChangedService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // -----------------------------------------------------------------------
  // 1. Points redemption — identity, persisted columns, SaleRecorded, ref decrement
  // -----------------------------------------------------------------------

  @Test
  void pointsRedemptionHoldsExtendedIdentityPersistsColumnsAndDecrementsRefExactlyOnce()
      throws Exception {
    UUID memberId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    UUID itemId = createMenuItem(10_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            "points-" + UUID.randomUUID(),
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 8_000L),
            null,
            null,
            null,
            null,
            memberId,
            2_000L,
            null,
            null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    assertThat(order.totalMinor()).isEqualTo(8_000L);
    assertThat(order.breakdown().loyaltyRedeemedMinor()).isEqualTo(2_000L);
    assertThat(order.breakdown().discountMinor()).as("promo-only, excludes loyalty").isZero();

    // Extended identity: subtotal - discount - loyaltyRedeemed + serviceCharge + tax == amount.
    var bd = order.breakdown();
    assertThat(
            bd.subtotalMinor()
                - bd.discountMinor()
                - bd.loyaltyRedeemedMinor()
                + bd.serviceChargeMinor()
                + bd.taxMinor())
        .isEqualTo(bd.grandTotalMinor())
        .isEqualTo(8_000L);

    // The five persisted restaurant_order columns.
    Map<String, Object> row = readOrderRedemptionColumnsAdmin(order.orderId());
    assertThat(row.get("loyalty_member_id")).hasToString(memberId.toString());
    assertThat(((Number) row.get("loyalty_redeemed_points")).longValue()).isEqualTo(2_000L);
    assertThat(((Number) row.get("loyalty_redeemed_minor")).longValue()).isEqualTo(2_000L);
    assertThat(row.get("gift_card_id")).isNull();
    assertThat(row.get("gift_card_redeemed_minor")).isNull();

    // The decoded SaleRecorded wire shape.
    GenericRecord decoded = readSaleRecordedAdmin(order.saleId());
    assertThat(decoded.get("amount_minor")).isEqualTo(8_000L);
    assertThat(decoded.get("discount_minor")).as("PROMO-ONLY on the wire").isEqualTo(0L);
    assertThat(decoded.get("loyalty_member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(2_000L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(2_000L);
    assertThat(decoded.get("gift_card_id")).isNull();
    assertThat(decoded.get("gift_card_redeemed_minor")).isNull();

    // member_balance_ref decremented exactly once: 3,000 - 2,000 = 1,000.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(1_000L);
  }

  // -----------------------------------------------------------------------
  // 2. Gift-card redemption — partial (residual cash) and full (zero-amount capture)
  // -----------------------------------------------------------------------

  @Test
  void giftCardPartialRedemptionSettlesResidualAsCashAndDecrementsRefExactlyOnce()
      throws Exception {
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID itemId = createMenuItem(50_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            "giftcard-partial-" + UUID.randomUUID(),
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 30_000L),
            null,
            null,
            null,
            null,
            null,
            null,
            cardId,
            20_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    // A gift-card redemption is a TENDER settlement, never a discount: the grand total is
    // unaffected.
    assertThat(order.totalMinor()).isEqualTo(50_000L);
    PaymentResponse payment = order.payment();
    assertThat(payment.amountMinor()).as("the residual, not the grand total").isEqualTo(30_000L);
    assertThat(payment.tenderedMinor()).isEqualTo(30_000L);
    assertThat(payment.changeMinor()).isZero();

    GenericRecord decoded = readSaleRecordedAdmin(order.saleId());
    assertThat(decoded.get("amount_minor")).isEqualTo(50_000L);
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(20_000L);

    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);
  }

  @Test
  void giftCardFullCoverageRecordsZeroAmountCapturedPaymentWithNullTenderType() throws Exception {
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 50_000L, "IDR", 1L);
    UUID itemId = createMenuItem(50_000L);

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            "giftcard-full-" + UUID.randomUUID(),
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 0L),
            null,
            null,
            null,
            null,
            null,
            null,
            cardId,
            50_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    assertThat(order.totalMinor()).isEqualTo(50_000L);
    PaymentResponse payment = order.payment();
    assertThat(payment.amountMinor()).isZero();
    assertThat(payment.status()).isEqualTo("CAPTURED");

    GenericRecord decoded = readSaleRecordedAdmin(order.saleId());
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
    UUID unknownMember = UUID.randomUUID(); // never replicated via LoyaltyBalanceChanged
    UUID itemId = createMenuItem(10_000L);
    String idemKey = "unknown-member-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            null,
            null,
            null,
            unknownMember,
            500L,
            null,
            null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request)))
        .isInstanceOf(LoyaltyBalanceInsufficientException.class);

    assertThat(countOrdersAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  @Test
  void unknownGiftCardRejectsCheckoutAndWritesNothing() throws Exception {
    UUID unknownCard = UUID.randomUUID(); // never replicated via GiftCardStateChanged
    UUID itemId = createMenuItem(10_000L);
    String idemKey = "unknown-card-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            null,
            null,
            null,
            null,
            null,
            unknownCard,
            5_000L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request)))
        .isInstanceOf(GiftCardUnusableException.class);

    assertThat(countOrdersAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  @Test
  void depletedGiftCardRejectsCheckoutAndWritesNothing() throws Exception {
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "DEPLETED", 0L, "IDR", 1L);
    UUID itemId = createMenuItem(10_000L);
    String idemKey = "depleted-card-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            null,
            null,
            null,
            null,
            null,
            null,
            cardId,
            5_000L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request)))
        .isInstanceOf(GiftCardUnusableException.class);

    assertThat(countOrdersAdmin(idemKey)).isZero();
    assertThat(countSaleRecordedEventsAdmin()).isZero();
  }

  // -----------------------------------------------------------------------
  // 4. Idempotent replay — no second decrement, no second SaleRecorded
  // -----------------------------------------------------------------------

  @Test
  void idempotentReplayDoesNotDoubleDecrementOrEmitASecondEvent() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID cardId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID itemId = createMenuItem(50_000L);
    String idemKey = "replay-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 44_000L),
            null,
            null,
            null,
            null,
            memberId,
            1_000L,
            cardId,
            5_000L);

    CheckoutResult first = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));
    assertThat(first.created()).isTrue();
    UUID orderId = first.order().orderId();

    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(25_000L);
    assertThat(countSaleRecordedEventsAdmin()).isEqualTo(1);

    CheckoutResult replay = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));

    assertThat(replay.created()).isFalse();
    assertThat(replay.order().orderId()).isEqualTo(orderId);
    // No second decrement.
    assertThat(readMemberBalanceAdmin(memberId)).as("no second decrement").isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).as("no second decrement").isEqualTo(25_000L);
    // No second SaleRecorded.
    assertThat(countSaleRecordedEventsAdmin()).as("no second event").isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // 5. Digital-tender capture path — full order total, refs decremented ONCE overall
  // -----------------------------------------------------------------------

  @Test
  void digitalCaptureEmitsSaleRecordedWithTheOrderGrandTotalNotTheResidual() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID cardId = UUID.randomUUID();
    seedMemberBalance(memberId, 3_000L, 1L);
    seedGiftCard(cardId, "ACTIVE", 30_000L, "IDR", 1L);
    UUID itemId = createMenuItem(100_000L);
    String idemKey = "digital-capture-" + UUID.randomUUID();

    CheckoutRequest request =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.QRIS, null),
            null,
            null,
            null,
            null,
            memberId,
            1_000L,
            cardId,
            20_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(request));
    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    // grandTotal = 100,000 - 1,000 (loyalty) = 99,000; residual = 99,000 - 20,000 (gift card) =
    // 79,000; digital + residual > 0 -> PENDING, no sale yet.
    assertThat(order.totalMinor()).isEqualTo(99_000L);
    assertThat(order.saleId()).isNull();
    PaymentResponse pending = order.payment();
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.amountMinor()).isEqualTo(79_000L);

    // Redemption already applied at CHECKOUT time — before any capture.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);

    PaymentResponse captured =
        TenantContext.callAs(TENANT, ACTOR, () -> paymentCaptureService.capture(pending.paymentId()));
    assertThat(captured.status()).isEqualTo("CAPTURED");
    assertThat(captured.saleId()).isNotNull();

    GenericRecord decoded = readSaleRecordedAdmin(captured.saleId());
    // The under-recording fix under test: amount_minor MUST be the order's GRAND TOTAL (99,000),
    // never payment.amountMinor() (the 79,000 tender RESIDUAL after the gift-card settlement).
    assertThat(decoded.get("amount_minor")).as("order grand total, NOT the residual").isEqualTo(99_000L);
    assertThat(decoded.get("loyalty_member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(1_000L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(1_000L);
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(20_000L);
    assertThat(decoded.get("discount_minor")).as("promo-only, excludes loyalty").isEqualTo(0L);

    // Refs decremented exactly ONCE overall — capture does not decrement a second time.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(2_000L);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(10_000L);
  }

  // -----------------------------------------------------------------------
  // 6. Park / pay-parked — redemption evaluated at PAY time, never at park time (restaurant only)
  // -----------------------------------------------------------------------

  @Test
  void payParkedWithLoyaltyRedemptionAppliesAtPayTimeNotParkTime() throws Exception {
    UUID memberId = UUID.randomUUID();
    seedMemberBalance(memberId, 5_000L, 1L);
    UUID itemId = createMenuItem(20_000L);

    ParkOrderRequest parkRequest =
        new ParkOrderRequest(
            BUSINESS_ID, "park-loyalty-" + UUID.randomUUID(), List.of(new OrderLineRequest(itemId, 1)));
    CheckoutResult parked = TenantContext.callAs(TENANT, ACTOR, () -> orderService.park(parkRequest));
    assertThat(parked.created()).isTrue();
    UUID orderId = parked.order().orderId();

    // ParkOrderRequest carries no loyalty fields — the balance is untouched at park time.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(5_000L);

    PayParkedRequest payRequest =
        new PayParkedRequest(
            new PaymentRequest(TenderType.CASH, 18_500L), null, memberId, 1_500L, null, null);
    OrderResponse paid =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.payParked(orderId, payRequest));

    assertThat(paid.status()).isEqualTo("COMPLETED");
    assertThat(paid.totalMinor()).isEqualTo(18_500L);
    assertThat(paid.saleId()).isNotNull();

    // The redemption is applied only NOW, at pay time.
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(3_500L);

    GenericRecord decoded = readSaleRecordedAdmin(paid.saleId());
    assertThat(decoded.get("loyalty_member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(1_500L);
  }

  // -----------------------------------------------------------------------
  // Fixtures / helpers
  // -----------------------------------------------------------------------

  private UUID createMenuItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS_ID, "Item " + UUID.randomUUID(), "BEVERAGE", priceMinor, "IDR"))
                .id());
  }

  /** Seeds {@code member_balance_ref} via the consumer path — the same as a real replicated event. */
  private void seedMemberBalance(UUID memberId, long points, long seq) {
    loyaltyBalanceChangedService.apply(
        new LoyaltyBalanceChangedEvent(UUID.randomUUID(), memberId, TENANT, points, seq, Instant.now()));
  }

  /** Seeds {@code gift_card_ref} via the consumer path — the same as a real replicated event. */
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

  private Map<String, Object> readOrderRedemptionColumnsAdmin(UUID orderId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor,"
                    + " gift_card_id, gift_card_redeemed_minor FROM restaurant_order WHERE id = ?")) {
      ps.setObject(1, orderId);
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

  private int countOrdersAdmin(String idempotencyKey) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COUNT(*) FROM restaurant_order WHERE idempotency_key = ?")) {
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

  /** Reads the latest {@code SaleRecorded} outbox row for {@code saleId} and decodes it. */
  private GenericRecord readSaleRecordedAdmin(UUID saleId) {
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'SaleRecorded' AND aggregate_id = ?"
                + " ORDER BY occurred_at DESC LIMIT 1",
            saleId.toString());
    return AvroSerde.deserialize((byte[]) outboxRow.get("payload"), SaleRecordedSchema.schema());
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
