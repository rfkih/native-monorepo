package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

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
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrent capture of the SAME pending digital ticket (review W2, mirroring {@code
 * TicketCheckoutConcurrencyTest}'s barrier harness): two threads race {@code POST
 * /tickets/{id}/capture}.
 *
 * <p>The winner's transaction captures the payment and emits the {@code SaleRecorded}; the loser's
 * flush trips the optimistic {@code @Version} BEFORE any event write, and {@code
 * TicketService.capture} recovers it with a fresh-transaction re-read — so BOTH callers succeed
 * with the same CAPTURED ticket, and exactly ONE {@code SaleRecorded} exists. This is the
 * two-terminals-marking-the-same-QRIS-paid race on a money path: the invariant under test is "never
 * a second revenue event".
 */
@SpringBootTest
class TicketCaptureConcurrencyTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentCaptureOfTheSameTicketEmitsExactlyOneSaleRecorded() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, CARWASH, true));

    CatalogItemResponse pkg =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Basic Wash", null, 40_000_00L, "IDR")));

    // A digital checkout: PENDING payment, no sale, no events yet.
    CheckoutResult pending =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                ticketService.checkout(
                    new CheckoutRequest(
                        OUTLET,
                        "capture-race-1",
                        "bay-1",
                        null,
                        null,
                        null,
                        List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
                        new PaymentRequest(TenderType.QRIS, null))));
    UUID ticketId = pending.ticket().ticketId();
    assertThat(pending.ticket().saleId()).isNull();
    assertThat(saleRecordedCount()).isZero();

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<TicketResponse> attempt =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return ticketService.capture(ticketId);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TicketResponse> f1 = pool.submit(attempt);
      Future<TicketResponse> f2 = pool.submit(attempt);

      // Neither call throws (no 500): the optimistic-lock loser resolves to the winner's result.
      TicketResponse r1 = f1.get();
      TicketResponse r2 = f2.get();

      assertThat(r1.payment().status()).isEqualTo("CAPTURED");
      assertThat(r2.payment().status()).isEqualTo("CAPTURED");
      assertThat(r1.saleId()).isNotNull().isEqualTo(r2.saleId());
    } finally {
      pool.shutdownNow();
    }

    assertThat(saleRecordedCount())
        .as("two racing captures must never emit a second revenue event")
        .isEqualTo(1L);
  }

  private Long saleRecordedCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
  }
}
