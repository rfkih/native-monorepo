package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.carwash.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.carwash.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.carwash.promotion.service.PromotionAdminService;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
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
 * Concurrency proof for the single-use coupon redemption guard (ADR 0026) — mirrors restaurant-
 * service's {@code CouponRedemptionConcurrencyTest} and carwash's own {@code
 * TicketCheckoutConcurrencyTest} two-thread {@link CyclicBarrier} harness.
 *
 * <p>Two threads check out DIFFERENT tickets (distinct idempotency keys — both are genuinely NEW
 * checkouts, not idempotent replays of each other) using the SAME single-use ({@code
 * max_redemptions = 1}) coupon code, released simultaneously. Exactly one must succeed and redeem
 * the coupon; the other must lose the atomic {@code redeemIfAvailable} race and receive {@link
 * CouponExhaustedException} (409 via {@code config.PromotionAdvice}). {@code coupon.redeemed_count}
 * must end at exactly 1 — never 0 (lost update) or 2 (double redemption).
 */
@SpringBootTest
class CouponRedemptionConcurrencyTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "88888888-8888-8888-8888-888888888888";
  private static final String ACTOR = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("88888888-8888-8888-8888-888888888801");
  private static final String CARWASH = "carwash";

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void concurrentCheckoutsWithTheSameSingleUseCouponYieldExactlyOneRedemption() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, CARWASH, true));

    UUID packageId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    catalogService.createPackage(
                        new CatalogItemCreateRequest(OUTLET, "Basic Wash", null, 10_000L, "IDR")))
            .id();

    UUID ruleId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                promotionAdminService
                    .createRule(
                        new PromoRuleCreateRequest(
                            "10% off",
                            "PERCENT_OFF_ORDER",
                            null,
                            null,
                            1_000L,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            // requiresCoupon: the race under test is over the COUPON's redemption.
                            true,
                            LocalDate.of(2026, 1, 1),
                            null))
                    .id());

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            promotionAdminService.createCoupon(new CouponCreateRequest("RACE10", ruleId, 1, null)));

    String keyA = "coupon-race-" + UUID.randomUUID() + "-a";
    String keyB = "coupon-race-" + UUID.randomUUID() + "-b";
    CheckoutRequest reqA =
        new CheckoutRequest(
            OUTLET,
            keyA,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            "RACE10");
    CheckoutRequest reqB =
        new CheckoutRequest(
            OUTLET,
            keyB,
            "bay-2",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            "RACE10");

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
    int exhaustedCount = 0;
    try {
      Future<CheckoutResult> fA = pool.submit(attemptA);
      Future<CheckoutResult> fB = pool.submit(attemptB);

      for (Future<CheckoutResult> f : List.of(fA, fB)) {
        try {
          CheckoutResult result = f.get();
          assertThat(result.created()).isTrue();
          successCount++;
        } catch (ExecutionException ex) {
          assertThat(ex.getCause()).isInstanceOf(CouponExhaustedException.class);
          exhaustedCount++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(successCount).as("exactly one checkout redeemed the coupon").isEqualTo(1);
    assertThat(exhaustedCount).as("the loser sees CouponExhaustedException").isEqualTo(1);
    assertThat(redeemedCountAsAdmin("RACE10"))
        .as("redeemed_count settles at exactly 1")
        .isEqualTo(1);
  }

  /** Reads {@code coupon.redeemed_count} over an admin/BYPASSRLS connection. */
  private int redeemedCountAsAdmin(String code) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT redeemed_count FROM coupon WHERE code = ?")) {
      ps.setString(1, code);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
