package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.barbershop.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.barbershop.promotion.service.PromotionAdminService;
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
 * Concurrency proof for the single-use coupon redemption guard (ADR 0026) — mirrors carwash-
 * service's identical port of restaurant-service's {@code CouponRedemptionConcurrencyTest} and
 * barbershop's own {@code TicketCheckoutConcurrencyTest} two-thread {@link CyclicBarrier} harness.
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

  private static final String TENANT = "88888888-8888-8888-8888-888888888887";
  private static final String ACTOR = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("88888888-8888-8888-8888-888888888802");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void concurrentCheckoutsWithTheSameSingleUseCouponYieldExactlyOneRedemption() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, BARBERSHOP, true));

    UUID serviceId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    catalogService.createService(
                        new CatalogItemCreateRequest(OUTLET, "Haircut", null, 10_000L, "IDR", null)))
            .id();
    UUID barberId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    catalogService.createStaffProfile(
                        new StaffProfileCreateRequest(OUTLET, "Budi", null, true)))
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
        () -> promotionAdminService.createCoupon(new CouponCreateRequest("RACE10", ruleId, 1, null)));

    String keyA = "coupon-race-" + UUID.randomUUID() + "-a";
    String keyB = "coupon-race-" + UUID.randomUUID() + "-b";
    CheckoutRequest reqA =
        new CheckoutRequest(
            OUTLET,
            keyA,
            "chair-1",
            barberId,
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
            new PaymentRequest(TenderType.CASH, 10_000L),
            "RACE10");
    CheckoutRequest reqB =
        new CheckoutRequest(
            OUTLET,
            keyB,
            "chair-2",
            barberId,
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
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
    assertThat(redeemedCountAsAdmin("RACE10")).as("redeemed_count settles at exactly 1").isEqualTo(1);
  }

  /** Reads {@code coupon.redeemed_count} over an admin/BYPASSRLS connection. */
  private int redeemedCountAsAdmin(String code) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement("SELECT redeemed_count FROM coupon WHERE code = ?")) {
      ps.setString(1, code);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
