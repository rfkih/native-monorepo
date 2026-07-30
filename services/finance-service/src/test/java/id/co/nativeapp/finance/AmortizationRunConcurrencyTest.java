package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.DeferralWriter;
import id.co.nativeapp.finance.assets.service.RunAmortizationResult;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CONCURRENCY proof for the amortization run (§3.2 — atomicity + idempotency under concurrency for
 * a money-touching command). Two threads, released together by a {@link CyclicBarrier}, race {@code
 * run(period)} for the SAME {@code (company, period)}: the advisory lock + {@code
 * uq_amortization_run} UNIQUE serialize them — exactly one run row exists, both calls converge on
 * the same run id with exactly one {@code created == true}, and the month's share posts exactly
 * once (no double depreciation/amortization).
 */
@SpringBootTest
class AmortizationRunConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-cccccccccc88";
  private static final String ACTOR = "amort-race@integration.co.id";

  @Autowired private DeferralWriter deferralWriter;
  @Autowired private AmortizationRunWriter amortizationRunWriter;
  @Autowired private Clock clock;

  @Test
  void concurrentRunPostsExactlyOnce() throws Exception {
    String period = LedgerPosting.periodOf(clock.instant());

    // Seed one due item: a 6,000,000 / 6-month prepaid starting THIS month (share = 1,000,000).
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            deferralWriter.create(
                DeferralKind.PREPAID_EXPENSE, "Rent", 6_000_000L, 6, period, "IDR", "def-race-1"));

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    RunAmortizationResult r1;
    RunAmortizationResult r2;
    try {
      Future<RunAmortizationResult> f1 = pool.submit(race(barrier, period));
      Future<RunAmortizationResult> f2 = pool.submit(race(barrier, period));
      r1 = f1.get();
      r2 = f2.get();
    } finally {
      pool.shutdownNow();
    }

    // Both calls converged on the SAME run; exactly one freshly ran it.
    assertThat(r1.runId()).isEqualTo(r2.runId());
    assertThat(r1.created() ^ r2.created()).as("exactly one call created the run").isTrue();

    // Exactly ONE run row, and the month's 1,000,000 share posted exactly once (5000 debit).
    assertThat(countAsAdmin("SELECT count(*) FROM amortization_run")).isEqualTo(1L);
    assertThat(
            countAsAdmin(
                "SELECT COALESCE(SUM(debit_minor),0) FROM journal_line WHERE account_code='5000'"))
        .isEqualTo(1_000_000L);
  }

  private Callable<RunAmortizationResult> race(CyclicBarrier barrier, String period) {
    return () ->
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              barrier.await();
              return amortizationRunWriter.run(period);
            });
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
