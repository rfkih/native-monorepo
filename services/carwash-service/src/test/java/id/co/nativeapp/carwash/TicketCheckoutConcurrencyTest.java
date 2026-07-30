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
 * The concurrency-safe idempotency contract for ticket checkout (ENGINEERING-STANDARDS §3.2),
 * mirroring {@code RecordWashConcurrencyTest}: two threads check out with the SAME {@code
 * idempotencyKey} in the SAME tenant scope, the company being entitled to the carwash module.
 *
 * <p>Proves the unique-constraint-loser conflict-recovery branch ({@code
 * DataIntegrityViolationException} → separate-transaction re-read): exactly ONE {@code
 * carwash_ticket} row, exactly ONE {@code SaleRecorded} outbox row, and BOTH callers receive a
 * successful idempotent result (no exception, no 500) with exactly one {@code created=true} and one
 * {@code created=false}, both resolving to the same ticket id.
 */
@SpringBootTest
class TicketCheckoutConcurrencyTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentSameKeyYieldsOneTicketOneEventAndTwoSuccessfulResults() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, CARWASH, true));

    CatalogItemResponse pkg =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Basic Wash", null, 40_000_00L, "IDR")));

    String idempotencyKey = "race-ticket-123";
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idempotencyKey,
            "bay-1",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    // A barrier so both threads enter checkout as close to simultaneously as possible, maximizing
    // the chance both miss the idempotency short-circuit and race the INSERT.
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<CheckoutResult> attempt =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return ticketService.checkout(request);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CheckoutResult> f1 = pool.submit(attempt);
      Future<CheckoutResult> f2 = pool.submit(attempt);

      CheckoutResult r1 = f1.get();
      CheckoutResult r2 = f2.get();

      // Neither call throws (no 500) — both return a successful idempotent result.
      assertThat(r1.created() ^ r2.created())
          .as("exactly one caller created the ticket, the other was idempotent")
          .isTrue();
      assertThat(r1.ticket().ticketId())
          .as("both callers resolve to the same single ticket")
          .isEqualTo(r2.ticket().ticketId());
    } finally {
      pool.shutdownNow();
    }

    assertThat(ticketRowCountAsAdmin()).isEqualTo(1L);
    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
    assertThat(outboxCount).isEqualTo(1L);
  }

  private long ticketRowCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM carwash_ticket")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
