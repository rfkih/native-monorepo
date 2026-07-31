package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.domain.OfflineReplayInvalidException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Offline mode (Phase 5, ADR 0028) — the carwash ticket checkout mirror. A cash-only ticket queued
 * client-side while offline is REPLAYED through this SAME {@code POST /checkout} endpoint;
 * exactly-once delivery rides the existing {@code (company_id, idempotencyKey)} uniqueness (a
 * replay is just a retry, {@link TicketCheckoutAcceptanceTest} already proves the general
 * idempotent-retry contract). This class proves the offline-specific request-shape validation:
 * {@code clientOccurredAt} bounds, the {@code offlineReplay}/{@code clientOccurredAt} pairing, the
 * forbidden-field matrix, and that a valid {@code clientOccurredAt} becomes the persisted ticket's
 * {@code occurredAt}.
 */
@SpringBootTest
class OfflineReplayCheckoutTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "attendant-offline@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // -----------------------------------------------------------------------
  // Bounds matrix
  // -----------------------------------------------------------------------

  @Test
  void clientOccurredAtMoreThan48HoursInThePastIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    Instant tooOld = Instant.now().minus(Duration.ofHours(49));
    CheckoutRequest request =
        offlineRequest(pkg.id(), "offline-49h-past", TenderType.CASH, null, null, null, tooOld);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void clientOccurredAtMoreThan5MinutesInTheFutureIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    Instant tooFuture = Instant.now().plus(Duration.ofMinutes(6));
    CheckoutRequest request =
        offlineRequest(
            pkg.id(), "offline-6min-future", TenderType.CASH, null, null, null, tooFuture);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void clientOccurredAtNear47HoursInThePastIsAcceptedAndPersistedAsOccurredAt() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    // Clamp-aware (ADR 0028 security review): the raw now-47h can itself fall before the start of
    // the current UTC month during the first ~47h of a month, which the guard's month clamp would
    // then reject — so the accepted instant is the LATER of now-47h and just-inside the month
    // start, mirroring the guard's own earliestAllowed = max(now-48h, startOfMonth) computation.
    // Accepted deterministically year-round, unlike a bare "now-47h".
    // Truncated to micros: Postgres TIMESTAMPTZ stores microseconds, and Windows Instant.now()
    // carries sub-micro precision — an untruncated instant would fail the round-trip equality.
    Instant now = Instant.now();
    Instant nearFortySevenHoursAgo =
        max(now.minus(Duration.ofHours(47)), startOfCurrentMonthUtc().plus(Duration.ofSeconds(1)))
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    CheckoutRequest request =
        offlineRequest(
            pkg.id(),
            "offline-47h-past",
            TenderType.CASH,
            null,
            null,
            null,
            nearFortySevenHoursAgo);

    CheckoutResult result = checkoutAs(request);

    assertThat(result.created()).isTrue();
    assertThat(result.ticket().occurredAt()).isEqualTo(nearFortySevenHoursAgo);
  }

  @Test
  void clientOccurredAtBeforeTheStartOfTheCurrentMonthIsRejectedEvenWithin48Hours()
      throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    // Always outside the accepted window regardless of today's date-of-month: caught by the 48h
    // bound early in the month, or by the month clamp later in the month — deterministic year-round
    // (ADR 0028 security review: a replay must never cross a month boundary into a possibly-FILED
    // prior period).
    Instant beforeCurrentMonth = startOfCurrentMonthUtc().minus(Duration.ofHours(1));
    CheckoutRequest request =
        offlineRequest(
            pkg.id(),
            "offline-month-clamp",
            TenderType.CASH,
            null,
            null,
            null,
            beforeCurrentMonth);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void clientOccurredAtWithoutOfflineReplayIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "offline-no-flag",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L),
            null,
            null,
            null,
            null,
            null,
            null, // offlineReplay = null
            Instant.now()); // clientOccurredAt present without offlineReplay = true -> 422

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  // -----------------------------------------------------------------------
  // Forbidden-fields matrix (offlineReplay = TRUE)
  // -----------------------------------------------------------------------

  @Test
  void offlineReplayWithANonCashTenderIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "offline-non-cash",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.QRIS, null),
            null,
            null,
            null,
            null,
            null,
            true,
            null);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void offlineReplayWithACouponCodeIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        offlineRequest(
            pkg.id(), "offline-coupon", TenderType.CASH, "SOMECODE", null, null, null);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void offlineReplayWithLoyaltyPointsRedemptionIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "offline-points",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L),
            null,
            UUID.randomUUID(),
            500L,
            null,
            null,
            true,
            null);

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void offlineReplayWithAGiftCardRedemptionIsRejectedWith422() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        offlineRequest(
            pkg.id(),
            "offline-giftcard",
            TenderType.CASH,
            null,
            null,
            UUID.randomUUID(),
            null);
    // giftCardRedeemMinor omitted above (0) but giftCardId alone must also be rejected — rebuild
    // with an explicit positive redemption amount to prove the amount half alone is caught too.
    CheckoutRequest withAmount =
        new CheckoutRequest(
            request.businessId(),
            "offline-giftcard-amt",
            request.bay(),
            request.vehiclePlate(),
            request.staffProfileId(),
            request.discountMinor(),
            request.lines(),
            request.payment(),
            request.couponCode(),
            request.loyaltyMemberId(),
            request.loyaltyRedeemPoints(),
            null,
            5_000L,
            request.offlineReplay(),
            request.clientOccurredAt());

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(OfflineReplayInvalidException.class);
    assertThatThrownBy(() -> checkoutAs(withAmount))
        .isInstanceOf(OfflineReplayInvalidException.class);
  }

  @Test
  void offlineReplayWithLoyaltyMemberOnlyIsAccepted() throws Exception {
    // loyaltyMemberId alone (earn attribution, no redemption) is exempt from the forbidden-fields
    // guard.
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "offline-member-only",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L),
            null,
            UUID.randomUUID(),
            null,
            null,
            null,
            true,
            null);

    CheckoutResult result = checkoutAs(request);

    assertThat(result.created()).isTrue();
  }

  // -----------------------------------------------------------------------
  // Upward reprice: under-tendered CASH is coerced up to the due amount (offline only)
  // -----------------------------------------------------------------------

  @Test
  void offlineReplayCoercesUnderTenderedCashUpToTheDueAmount() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    // Due = subtotal 5,000,000 + 11% VAT_CARWASH (illustrative, V5 seed for this tenant) 550,000
    // = 5,550,000.
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "offline-undertender-001",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 5_549_900L), // due 5,550,000 − 100
            null,
            null,
            null,
            null,
            null,
            true,
            Instant.now().minus(Duration.ofHours(1)));

    CheckoutResult result = checkoutAs(request);

    assertThat(result.created()).isTrue();
    assertThat(result.ticket().breakdown().grandTotalMinor()).isEqualTo(5_550_000L);
    assertThat(result.ticket().payment().tenderedMinor())
        .as("the server coerces the under-tendered offline CASH up to the due amount, not the"
            + " client's 5,549,900")
        .isEqualTo(5_550_000L);
    assertThat(result.ticket().payment().changeMinor()).isZero();
  }

  @Test
  void onlineUnderTenderedCashWithoutOfflineReplayIsRejected() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "online-undertender-001",
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 5_549_900L)); // 100 short; no offlineReplay

    assertThatThrownBy(() -> checkoutAs(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("less than the amount due");
  }

  // -----------------------------------------------------------------------
  // Idempotent replay regression
  // -----------------------------------------------------------------------

  @Test
  void anOfflineReplayCheckoutIsIdempotentOnRetry() throws Exception {
    grantCarwash(TENANT);
    CatalogItemResponse pkg = createPackage();
    Instant occurredAt = Instant.now().minus(Duration.ofHours(2));
    CheckoutRequest request =
        offlineRequest(
            pkg.id(), "offline-replay-retry-1", TenderType.CASH, null, null, null, occurredAt);

    CheckoutResult first = checkoutAs(request);
    assertThat(first.created()).isTrue();

    CheckoutResult retry = checkoutAs(request);
    assertThat(retry.created()).isFalse();
    assertThat(retry.ticket().ticketId()).isEqualTo(first.ticket().ticketId());

    assertThat(outboxRowCount("SaleRecorded")).isEqualTo(1L);
  }

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private CheckoutResult checkoutAs(CheckoutRequest request) throws Exception {
    return TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(request));
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private CheckoutRequest offlineRequest(
      UUID packageId,
      String idempotencyKey,
      TenderType tenderType,
      String couponCode,
      Long loyaltyRedeemPoints,
      UUID giftCardId,
      Instant clientOccurredAt) {
    return new CheckoutRequest(
        OUTLET,
        idempotencyKey,
        "bay-1",
        null,
        null,
        null,
        List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
        new PaymentRequest(tenderType, tenderType == TenderType.CASH ? 100_000_00L : null),
        couponCode,
        null,
        loyaltyRedeemPoints,
        giftCardId,
        null,
        true,
        clientOccurredAt);
  }

  private CatalogItemResponse createPackage() throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            catalogService.createPackage(
                new CatalogItemCreateRequest(OUTLET, "Offline Wash", null, 50_000_00L, "IDR")));
  }

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, "carwash", true));
  }

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

  private long outboxRowCount(String eventType) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT event_type FROM outbox WHERE event_type = ?", eventType);
    return rows.size();
  }
}
