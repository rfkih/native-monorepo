package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;

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
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
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
 * Concurrency proof for the loyalty/gift-card redemption atomic decrement (ADR 0027 decision 3) —
 * mirrors {@code CouponRedemptionConcurrencyTest}'s two-thread {@link CyclicBarrier} harness.
 *
 * <p>By the clamp design (ADR 0027), a KNOWN member/card whose cached balance is simply lower than
 * requested is never rejected — it is silently clamped to what remains. The 409 {@code
 * loyalty-balance-insufficient} / {@code gift-card-unusable} path for a KNOWN member/card is
 * reachable ONLY when the atomic decrement's {@code WHERE} clause loses a genuine concurrent race:
 * two checkouts read the SAME cached balance, both clamp to an amount the balance covers ONCE, and
 * the loser's {@code UPDATE ... WHERE balance >= :clamped} matches zero rows after the winner's
 * commit. Exactly one of the two concurrent checkouts must succeed; the loser must see 409 and
 * write NOTHING (no order, no event, no second decrement).
 */
@SpringBootTest
class LoyaltyGiftCardRedemptionConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "55555555-5555-5555-5555-555555555501";
  private static final String ACTOR = "cashier-race@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("55555555-5555-5555-5555-555555555502");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private LoyaltyBalanceChangedService loyaltyBalanceChangedService;
  @Autowired private GiftCardStateChangedService giftCardStateChangedService;

  @Test
  void concurrentPointsRedemptionsRacingTheSameBalanceYieldExactlyOneWinner() throws Exception {
    UUID memberId = UUID.randomUUID();
    // Only enough for ONE of the two concurrent 1,000-point redemptions, once both threads have
    // clamped against the SAME initial balance.
    seedMemberBalance(memberId, 1_500L, 1L);
    UUID itemId = createMenuItem(10_000L);

    CheckoutRequest reqA =
        pointsCheckoutRequest(itemId, memberId, 1_000L, "loyalty-race-a-" + UUID.randomUUID());
    CheckoutRequest reqB =
        pointsCheckoutRequest(itemId, memberId, 1_000L, "loyalty-race-b-" + UUID.randomUUID());

    RaceOutcome outcome = race(reqA, reqB, LoyaltyBalanceInsufficientException.class);

    assertThat(outcome.successCount()).as("exactly one checkout redeemed points").isEqualTo(1);
    assertThat(outcome.rejectedCount()).as("the loser sees 409").isEqualTo(1);
    // The winner's decrement landed exactly once: 1,500 - 1,000 = 500 (never negative from a
    // phantom second decrement).
    assertThat(readMemberBalanceAdmin(memberId)).isEqualTo(500L);
  }

  @Test
  void concurrentGiftCardRedemptionsRacingTheSameBalanceYieldExactlyOneWinner() throws Exception {
    UUID cardId = UUID.randomUUID();
    seedGiftCard(cardId, "ACTIVE", 15_000L, "IDR", 1L);
    UUID itemId = createMenuItem(50_000L);

    CheckoutRequest reqA =
        giftCardCheckoutRequest(itemId, cardId, 10_000L, "giftcard-race-a-" + UUID.randomUUID());
    CheckoutRequest reqB =
        giftCardCheckoutRequest(itemId, cardId, 10_000L, "giftcard-race-b-" + UUID.randomUUID());

    RaceOutcome outcome = race(reqA, reqB, GiftCardUnusableException.class);

    assertThat(outcome.successCount())
        .as("exactly one checkout redeemed the gift card")
        .isEqualTo(1);
    assertThat(outcome.rejectedCount()).as("the loser sees 409").isEqualTo(1);
    assertThat(readGiftCardBalanceAdmin(cardId)).isEqualTo(5_000L);
  }

  // -----------------------------------------------------------------------
  // Race harness
  // -----------------------------------------------------------------------

  private record RaceOutcome(int successCount, int rejectedCount) {}

  private RaceOutcome race(
      CheckoutRequest reqA,
      CheckoutRequest reqB,
      Class<? extends RuntimeException> expectedRejection)
      throws InterruptedException {
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<CheckoutResult> attemptA =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return orderService.checkout(reqA);
                });
    Callable<CheckoutResult> attemptB =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return orderService.checkout(reqB);
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
      UUID itemId, UUID memberId, long points, String idemKey) {
    return new CheckoutRequest(
        BUSINESS_ID,
        idemKey,
        List.of(new OrderLineRequest(itemId, 1)),
        new PaymentRequest(TenderType.CASH, 10_000L),
        null,
        null,
        null,
        null,
        memberId,
        points,
        null,
        null);
  }

  private CheckoutRequest giftCardCheckoutRequest(
      UUID itemId, UUID cardId, long redeemMinor, String idemKey) {
    return new CheckoutRequest(
        BUSINESS_ID,
        idemKey,
        List.of(new OrderLineRequest(itemId, 1)),
        new PaymentRequest(TenderType.CASH, 50_000L),
        null,
        null,
        null,
        null,
        null,
        null,
        cardId,
        redeemMinor);
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
                    new CreateMenuItemRequest(
                        BUSINESS_ID, "Item " + UUID.randomUUID(), "BEVERAGE", priceMinor, "IDR"))
                .id());
  }

  private void seedMemberBalance(UUID memberId, long points, long seq) {
    loyaltyBalanceChangedService.apply(
        new LoyaltyBalanceChangedEvent(
            UUID.randomUUID(), memberId, TENANT, points, seq, Instant.now()));
  }

  private void seedGiftCard(
      UUID cardId, String state, long balanceMinor, String currency, long seq) {
    giftCardStateChangedService.apply(
        new GiftCardStateChangedEvent(
            UUID.randomUUID(), cardId, TENANT, state, balanceMinor, currency, seq, Instant.now()));
  }

  private long readMemberBalanceAdmin(UUID memberId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT points_balance FROM member_balance_ref WHERE member_id = ?")) {
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
            admin.prepareStatement(
                "SELECT balance_minor FROM gift_card_ref WHERE gift_card_id = ?")) {
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
