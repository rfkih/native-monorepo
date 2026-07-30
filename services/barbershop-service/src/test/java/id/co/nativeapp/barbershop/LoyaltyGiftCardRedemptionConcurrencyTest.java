package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.loyaltyref.domain.GiftCardUnusableException;
import id.co.nativeapp.barbershop.loyaltyref.domain.LoyaltyBalanceInsufficientException;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.service.GiftCardStateChangedService;
import id.co.nativeapp.barbershop.loyaltyref.service.LoyaltyBalanceChangedService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Concurrency proof for the barbershop loyalty/gift-card redemption atomic decrement (ADR 0027
 * decision 3) — ported from carwash-service's {@code LoyaltyGiftCardRedemptionConcurrencyTest} (see
 * restaurant-service's original for why a KNOWN member/card with a lower balance is CLAMPED, never
 * rejected — the 409 path is reachable only via "unknown to this cache" or a genuine
 * concurrent-decrement loss).
 */
@SpringBootTest
class LoyaltyGiftCardRedemptionConcurrencyTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "55555555-5555-5555-5555-555555555501";
  private static final String OWNER_ACTOR = "owner-race@example.co.id";
  private static final String ACTOR = "cashier-race@example.co.id";
  private static final UUID OUTLET = UUID.fromString("55555555-5555-5555-5555-555555555502");

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private LoyaltyBalanceChangedService loyaltyBalanceChangedService;
  @Autowired private GiftCardStateChangedService giftCardStateChangedService;

  @Test
  void concurrentPointsRedemptionsRacingTheSameBalanceYieldExactlyOneWinner() throws Exception {
    grantBarbershop();
    UUID memberId = UUID.randomUUID();
    seedMemberBalance(memberId, 1_500L, 1L);
    UUID serviceId = createService(10_000L);
    UUID barberId = createStaffProfile();

    CheckoutRequest reqA =
        pointsCheckoutRequest(serviceId, barberId, memberId, 1_000L, "loyalty-race-a-" + UUID.randomUUID());
    CheckoutRequest reqB =
        pointsCheckoutRequest(serviceId, barberId, memberId, 1_000L, "loyalty-race-b-" + UUID.randomUUID());

    RaceOutcome outcome = race(reqA, reqB, LoyaltyBalanceInsufficientException.class);

    assertThat(outcome.successCount()).as("exactly one checkout redeemed points").isEqualTo(1);
    assertThat(outcome.rejectedCount()).as("the loser sees 409").isEqualTo(1);
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(500L);
  }

  @Test
  void concurrentGiftCardRedemptionsRacingTheSameBalanceYieldExactlyOneWinner() throws Exception {
    grantBarbershop();
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 15_000L, "IDR", 1L);
    UUID serviceId = createService(50_000L);
    UUID barberId = createStaffProfile();

    CheckoutRequest reqA =
        giftCardCheckoutRequest(serviceId, barberId, cardId, 10_000L, "giftcard-race-a-" + UUID.randomUUID());
    CheckoutRequest reqB =
        giftCardCheckoutRequest(serviceId, barberId, cardId, 10_000L, "giftcard-race-b-" + UUID.randomUUID());

    RaceOutcome outcome = race(reqA, reqB, GiftCardUnusableException.class);

    assertThat(outcome.successCount()).as("exactly one checkout redeemed the gift card").isEqualTo(1);
    assertThat(outcome.rejectedCount()).as("the loser sees 409").isEqualTo(1);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(5_000L);
  }

  // -----------------------------------------------------------------------
  // Race harness
  // -----------------------------------------------------------------------

  private record RaceOutcome(int successCount, int rejectedCount) {}

  private RaceOutcome race(
      CheckoutRequest reqA, CheckoutRequest reqB, Class<? extends RuntimeException> expectedRejection)
      throws InterruptedException {
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<CheckoutResult> attemptA =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return ticketService.checkout(reqA);
                });
    Callable<CheckoutResult> attemptB =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return ticketService.checkout(reqB);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    int successCount = 0;
    int rejectedCount = 0;
    try {
      Future<CheckoutResult> fA = pool.submit(attemptA);
      Future<CheckoutResult> fB = pool.submit(attemptB);

      for (Future<CheckoutResult> f : List.of(fA, fB)) {
        try {
          CheckoutResult result = f.get();
          assertThat(result.created()).isTrue();
          successCount++;
        } catch (ExecutionException ex) {
          assertThat(ex.getCause()).isInstanceOf(expectedRejection);
          rejectedCount++;
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return new RaceOutcome(successCount, rejectedCount);
  }

  private CheckoutRequest pointsCheckoutRequest(
      UUID serviceId, UUID barberId, UUID memberId, long points, String idemKey) {
    return new CheckoutRequest(
        OUTLET,
        idemKey,
        "chair-1",
        barberId,
        null,
        List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
        new PaymentRequest(TenderType.CASH, 10_000L),
        null,
        memberId,
        points,
        null,
        null);
  }

  private CheckoutRequest giftCardCheckoutRequest(
      UUID serviceId, UUID barberId, UUID cardId, long redeemMinor, String idemKey) {
    return new CheckoutRequest(
        OUTLET,
        idemKey,
        "chair-1",
        barberId,
        null,
        List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
        new PaymentRequest(TenderType.CASH, 50_000L),
        null,
        null,
        null,
        cardId,
        redeemMinor);
  }

  // -----------------------------------------------------------------------
  // Fixtures / helpers
  // -----------------------------------------------------------------------

  private void grantBarbershop() {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, "barbershop", true));
  }

  private UUID createService(long priceMinor) throws Exception {
    CatalogItemResponse response =
        TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Cut " + UUID.randomUUID(), null, priceMinor, "IDR", null)));
    return response.id();
  }

  private UUID createStaffProfile() throws Exception {
    StaffProfileResponse response =
        TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi " + UUID.randomUUID(), null, true)));
    return response.id();
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

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
