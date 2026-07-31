package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.domain.OfflineReplayValidationException;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 5 (ADR 0028) — offline-replay checkout: the {@code offlineReplay}/{@code clientOccurredAt}
 * contract enforced by {@code OfflineReplayGuard} and wired through {@code OrderWriter#checkout}.
 *
 * <p>A fresh tenant with NO seeded {@code tax_charge_rule} row, so grand total == subtotal (0% tax,
 * 0% service charge) and every assertion below stays a plain sum — mirrors {@code
 * LoyaltyGiftCardCheckoutIntegrationTest}'s fresh-tenant convention.
 *
 * <p>Covers: the {@code clientOccurredAt} bounds matrix (&gt;48h past / &gt;5min future / accepted
 * 47h-past with the persisted {@code occurred_at} verified), {@code clientOccurredAt} without
 * {@code offlineReplay} rejected, the offline-forbidden-field matrix (coupon / points / gift-card /
 * non-CASH tender / missing payment each rejected; a bare {@code loyaltyMemberId} accepted), and
 * one focused idempotent-replay-of-an-offline-sale regression.
 */
@SpringBootTest
class OfflineReplayCheckoutTest extends PostgresRlsTestBase {

  private static final String TENANT = "01010101-0101-0101-0101-010101010101";
  private static final String ACTOR = "cashier-offline@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // ---------------------------------------------------------------------------
  // clientOccurredAt bounds matrix
  // ---------------------------------------------------------------------------

  @Test
  void clientOccurredAtMoreThan48hInThePastIsRejected() throws Exception {
    UUID itemId = createMenuItem("Nasi Goreng", 15_000L);
    Instant tooOld = Instant.now().minus(Duration.ofHours(49));
    CheckoutRequest req = offlineCheckout("offline-past-001", itemId, tooOld);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("48h");

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  @Test
  void clientOccurredAtMoreThan5MinInTheFutureIsRejected() throws Exception {
    UUID itemId = createMenuItem("Mie Ayam", 12_000L);
    Instant tooFuture = Instant.now().plus(Duration.ofMinutes(6));
    CheckoutRequest req = offlineCheckout("offline-future-001", itemId, tooFuture);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("5 minutes");

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  @Test
  void clientOccurredAtNear47hInThePastIsAcceptedAndPersistedAsTheOccurredAt() throws Exception {
    UUID itemId = createMenuItem("Soto Betawi", 20_000L);
    // Clamp-aware (ADR 0028 security review): the raw now-47h can itself fall before the start of
    // the current UTC month during the first ~47h of a month, which the guard's month clamp would
    // then reject — so the accepted instant is the LATER of now-47h and just-inside the month
    // start, mirroring the guard's own earliestAllowed = max(now-48h, startOfMonth) computation.
    // Accepted deterministically year-round, unlike a bare "now-47h".
    // Truncated to micros: Postgres TIMESTAMPTZ stores microseconds, and the underlying clock can
    // carry sub-micro precision — an untruncated instant would fail the round-trip equality below.
    Instant now = Instant.now();
    Instant clientOccurredAt =
        max(now.minus(Duration.ofHours(47)), startOfCurrentMonthUtc().plus(Duration.ofSeconds(1)))
            .truncatedTo(ChronoUnit.MICROS);
    CheckoutRequest req = offlineCheckout("offline-accepted-001", itemId, clientOccurredAt);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().totalMinor()).isEqualTo(20_000L); // no tax/SC seeded for this tenant

    // The persisted order's occurred_at is the CLIENT-supplied instant, not the sync-time now().
    Instant persistedOccurredAt = occurredAtAsAdmin(result.order().orderId());
    assertThat(persistedOccurredAt).isEqualTo(clientOccurredAt);

    // The SaleRecorded outbox payload carries the SAME instant (drives the GL period).
    GenericRecord decoded = decodeSoleSaleRecorded();
    assertThat(decoded.get("occurred_at")).isEqualTo(clientOccurredAt.toEpochMilli());
  }

  @Test
  void clientOccurredAtBeforeTheStartOfTheCurrentMonthIsRejectedEvenWithin48h() throws Exception {
    UUID itemId = createMenuItem("Nasi Uduk", 15_000L);
    // Always outside the accepted window regardless of today's date-of-month: caught by the 48h
    // bound early in the month, or by the month clamp later in the month — deterministic year-round
    // (ADR 0028 security review: a replay must never cross a month boundary into a possibly-FILED
    // prior period).
    Instant beforeCurrentMonth = startOfCurrentMonthUtc().minus(Duration.ofHours(1));
    CheckoutRequest req = offlineCheckout("offline-month-clamp-001", itemId, beforeCurrentMonth);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class);

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  // ---------------------------------------------------------------------------
  // clientOccurredAt without offlineReplay
  // ---------------------------------------------------------------------------

  @Test
  void clientOccurredAtWithoutOfflineReplayIsRejected() throws Exception {
    UUID itemId = createMenuItem("Es Cendol", 8_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "offline-no-flag-001",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 1_000_000L),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null, // offlineReplay = null (false)
            Instant.now().minus(Duration.ofHours(1))); // clientOccurredAt supplied anyway

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("offlineReplay=true");

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  // ---------------------------------------------------------------------------
  // Offline forbidden-field matrix
  // ---------------------------------------------------------------------------

  @Test
  void offlineReplayWithACouponCodeIsRejected() throws Exception {
    UUID itemId = createMenuItem("Bakso Malang", 18_000L);
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-coupon-001", itemId, "SAVE10", null, null, null, null, TenderType.CASH);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("couponCode");
  }

  @Test
  void offlineReplayWithAPositivePointsRedemptionIsRejected() throws Exception {
    UUID itemId = createMenuItem("Sate Ayam", 22_000L);
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-points-001",
            itemId,
            null,
            UUID.randomUUID(),
            500L,
            null,
            null,
            TenderType.CASH);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("loyaltyRedeemPoints");
  }

  @Test
  void offlineReplayWithAGiftCardIdIsRejected() throws Exception {
    UUID itemId = createMenuItem("Gado Gado", 16_000L);
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-giftcard-001",
            itemId,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            TenderType.CASH);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("gift card");
  }

  @Test
  void offlineReplayWithAGiftCardRedeemAmountIsRejected() throws Exception {
    UUID itemId = createMenuItem("Rawon", 25_000L);
    // giftCardId deliberately null here to isolate the giftCardRedeemMinor-only branch — a bare
    // giftCardId (no amount) is asserted separately above.
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-giftcard-amount-001", itemId, null, null, null, null, 5_000L, TenderType.CASH);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("giftCardRedeemMinor");
  }

  @Test
  void offlineReplayWithANonCashTenderIsRejected() throws Exception {
    UUID itemId = createMenuItem("Ayam Geprek", 17_000L);
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-digital-001", itemId, null, null, null, null, null, TenderType.QRIS);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("CASH");
  }

  @Test
  void offlineReplayWithNoPaymentAtAllIsRejected() throws Exception {
    UUID itemId = createMenuItem("Nasi Padang", 30_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "offline-nopayment-001",
            List.of(new OrderLineRequest(itemId, 1)),
            null, // no payment at all
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            Instant.now().minus(Duration.ofHours(1)));

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OfflineReplayValidationException.class)
        .hasMessageContaining("CASH");
  }

  @Test
  void offlineReplayWithALoyaltyMemberIdAloneIsAccepted() throws Exception {
    UUID itemId = createMenuItem("Pecel Lele", 14_000L);
    CheckoutRequest req =
        fullOfflineRequest(
            "offline-member-only-001",
            itemId,
            null,
            UUID.randomUUID(),
            null, // no points redeemed — earn-only attribution
            null,
            null,
            TenderType.CASH);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().totalMinor()).isEqualTo(14_000L);
  }

  // ---------------------------------------------------------------------------
  // Idempotent replay of an offline sale
  // ---------------------------------------------------------------------------

  @Test
  void replayingTheSameOfflineIdempotencyKeyTwiceYieldsOneOrder() throws Exception {
    UUID itemId = createMenuItem("Nasi Kuning", 13_000L);
    Instant clientOccurredAt =
        Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(2));
    CheckoutRequest req = offlineCheckout("offline-replay-twice-001", itemId, clientOccurredAt);

    CheckoutResult first = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));
    CheckoutResult second = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.order().orderId()).isEqualTo(first.order().orderId());
    assertThat(rowCountAsAdmin("restaurant_order")).isEqualTo(1L);
    assertThat(saleRecordedRows()).hasSize(1);
  }

  // ---------------------------------------------------------------------------
  // Upward reprice: under-tendered CASH is coerced up to the due amount (offline only)
  // ---------------------------------------------------------------------------

  @Test
  void offlineReplayCoercesUnderTenderedCashUpToTheDueAmount() throws Exception {
    UUID itemId = createMenuItem("Es Teh Manis", 20_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "offline-undertender-001",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 19_900L), // due 20,000 (no tax/SC seeded) − 100
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            Instant.now().minus(Duration.ofHours(1)));

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().totalMinor()).isEqualTo(20_000L);
    assertThat(result.order().payment().tenderedMinor())
        .as(
            "the server coerces the under-tendered offline CASH up to the due amount, not the"
                + " client's 19,900")
        .isEqualTo(20_000L);
    assertThat(result.order().payment().changeMinor()).isZero();
  }

  @Test
  void onlineUnderTenderedCashWithoutOfflineReplayIsRejected() throws Exception {
    UUID itemId = createMenuItem("Es Jeruk", 20_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "online-undertender-001",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 19_900L)); // 100 short of the 20,000 due; no replay

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("less than the amount due");

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  /** Mirrors {@code OfflineReplayGuard}'s own month-clamp computation. */
  private static Instant startOfCurrentMonthUtc() {
    return YearMonth.from(Instant.now().atZone(ZoneOffset.UTC))
        .atDay(1)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant();
  }

  private UUID createMenuItem(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          CreateMenuItemRequest req =
              new CreateMenuItemRequest(BUSINESS_ID, name, "MAIN", priceMinor, "IDR");
          return menuService.createItem(req).id();
        });
  }

  /**
   * A minimal valid offline-replay checkout: one line, CASH payment, the given clientOccurredAt.
   */
  private CheckoutRequest offlineCheckout(
      String idempotencyKey, UUID itemId, Instant clientOccurredAt) {
    return new CheckoutRequest(
        BUSINESS_ID,
        idempotencyKey,
        List.of(new OrderLineRequest(itemId, 1)),
        new PaymentRequest(TenderType.CASH, 1_000_000L),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        clientOccurredAt);
  }

  /** Full-shape offline checkout request for the forbidden-field matrix. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  private CheckoutRequest fullOfflineRequest(
      String idempotencyKey,
      UUID itemId,
      String couponCode,
      UUID loyaltyMemberId,
      Long loyaltyRedeemPoints,
      UUID giftCardId,
      Long giftCardRedeemMinor,
      TenderType tenderType) {
    return new CheckoutRequest(
        BUSINESS_ID,
        idempotencyKey,
        List.of(new OrderLineRequest(itemId, 1)),
        new PaymentRequest(tenderType, 1_000_000L),
        null,
        null,
        null,
        couponCode,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
        true,
        Instant.now().minus(Duration.ofHours(1)));
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

  private Instant occurredAtAsAdmin(UUID orderId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT occurred_at FROM restaurant_order WHERE id = '" + orderId + "'")) {
      rs.next();
      Timestamp ts = rs.getTimestamp(1);
      return ts.toInstant();
    }
  }

  private List<Map<String, Object>> saleRecordedRows() {
    return jdbcTemplate.queryForList(
        "SELECT event_type, payload FROM outbox WHERE event_type = 'SaleRecorded'"
            + " ORDER BY occurred_at");
  }

  private GenericRecord decodeSoleSaleRecorded() {
    List<Map<String, Object>> rows = saleRecordedRows();
    assertThat(rows).hasSize(1);
    return AvroSerde.deserialize(
        (byte[]) rows.getFirst().get("payload"), SaleRecordedSchema.schema());
  }
}
