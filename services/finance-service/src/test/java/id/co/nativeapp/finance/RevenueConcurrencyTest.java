package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.revenue.service.RevenueReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CONCURRENCY proof for the consolidated-revenue read model (§3.2 — atomicity + idempotency under
 * concurrency for a money-touching consumer).
 *
 * <p>Two DISTINCT {@code SaleRecorded} events (different event ids, different sales) for the SAME
 * {@code (company_id, period, currency)} are posted concurrently — exactly the case that, under the
 * old read-modify-write accumulation, either lost an update or tripped an optimistic-lock / unique
 * conflict that the error handler would DLT-drop (silently losing valid revenue). The fix
 * accumulates the read model with an atomic {@code INSERT … ON CONFLICT … DO UPDATE}, so:
 *
 * <ul>
 *   <li>the {@code consolidated_revenue} total equals the SUM of both amounts (no lost update); and
 *   <li>BOTH events produce a {@code ledger_posting} (two rows — none dropped).
 * </ul>
 *
 * <p>A {@link CyclicBarrier} releases both threads into {@link RevenuePostingService#handle} as
 * close to simultaneously as possible, maximising the chance they race the same accumulator row.
 * Posting straight through the service (rather than over Kafka) keeps the concurrency
 * deterministic; the Kafka transport is exercised by the consume / idempotency / DLT tests.
 * Awaitility is not needed — the work is synchronous on both threads and joined via {@link
 * Future#get()}.
 */
@SpringBootTest
class RevenueConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "cashier-a@example.co.id";

  @Autowired private RevenuePostingService postingService;
  @Autowired private RevenueReader revenueReader;

  @Test
  void concurrentDistinctSalesForOneKeyAccumulateTheSumWithNoLostUpdate() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";
    long amountOne = 1_500_000L;
    long amountTwo = 900_000L;

    SaleRecordedEvent saleOne =
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT_A, BUSINESS_A, Money.ofMinor(amountOne, "IDR"), occurredAt);
    SaleRecordedEvent saleTwo =
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT_A, BUSINESS_A, Money.ofMinor(amountTwo, "IDR"), occurredAt);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> f1 = pool.submit(post(barrier, saleOne));
      Future<Boolean> f2 = pool.submit(post(barrier, saleTwo));

      // Both deliveries posted (first delivery for each distinct event id) — neither was dropped.
      assertThat(f1.get()).as("first sale posted").isTrue();
      assertThat(f2.get()).as("second sale posted").isTrue();
    } finally {
      pool.shutdownNow();
    }

    // The read model holds the SUM of both amounts — no lost update from the concurrent accumulate.
    Optional<Money> revenue =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> revenueReader.revenueForPeriod(period));
    assertThat(revenue).contains(Money.ofMinor(amountOne + amountTwo, "IDR"));

    // Exactly ONE consolidated_revenue row for the key (the upsert collapsed both onto it)...
    assertThat(revenueRowCountAsAdmin()).isEqualTo(1L);
    // ...and BOTH events produced a ledger posting — two rows, none DLT-dropped.
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L);
  }

  private Callable<Boolean> post(CyclicBarrier barrier, SaleRecordedEvent event) {
    return () ->
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              barrier.await();
              return postingService.handle(event);
            });
  }

  private long ledgerCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM ledger_posting");
  }

  private long revenueRowCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM consolidated_revenue");
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
