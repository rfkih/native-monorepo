package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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
 * CONCURRENCY proof for the FIRST-TWO-RUNS supersession double-count CRITICAL (#23).
 *
 * <p>Two GENUINELY NEW runs for the same {@code (company, period)} — {@code run_seq=1} and {@code
 * run_seq=2} — are posted from parallel transactions. Under the old {@code SELECT ... FOR UPDATE}
 * on the highest existing run row, NO lock was taken when no run row existed yet: in
 * READ_COMMITTED, neither transaction could see the other's run, so neither reversed the other,
 * both posted a PRIMARY, and the period DOUBLE-COUNTED (run1 + run2). The fix takes a deterministic
 * per-{@code (company, period)} {@code pg_advisory_xact_lock} at the TOP of the writer, before any
 * run-row read/insert, so the later run serializes behind the earlier run's row creation and
 * correctly sees-and-reverses (or self-supersedes) it.
 *
 * <p>The outcome is deterministic regardless of which thread wins the advisory lock:
 *
 * <ul>
 *   <li>if {@code run_seq=1} commits first, {@code run_seq=2} finds it as a prior ACTIVE run and
 *       reverses it (run 1 SUPERSEDED, the contra is tagged with run 1);
 *   <li>if {@code run_seq=2} commits first, {@code run_seq=1} sees a higher ACTIVE run and
 *       self-supersedes (posts its PRIMARY then its own compensating REVERSAL, run 1 SUPERSEDED).
 * </ul>
 *
 * Either way the period nets to run 2 ONLY — never run1 + run2 — with exactly 3 ledger rows (two
 * PRIMARYs + one REVERSAL) and exactly one reversal. A {@link CyclicBarrier} releases both threads
 * into {@link LaborCostPostingService#handle} as close to simultaneously as possible to maximise
 * the race. WITHOUT the advisory lock this test fails: the P&L would be run1 + run2 and there would
 * be two PRIMARYs and no reversal.
 */
@SpringBootTest
class LaborSupersessionConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PnlReader pnlReader;

  @Test
  void twoNewRunsPostedConcurrentlyNetToRunTwoOnlyWithNoDoubleCount() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    long run1Amount = 20_000_000L;
    long run2Amount = 22_000_000L; // the corrected (higher-seq) run

    LaborCostAllocatedEvent run1Event =
        event(UUID.randomUUID(), run1, 1, period, "5100-SALARY", run1Amount, occurredAt);
    LaborCostAllocatedEvent run2Event =
        event(UUID.randomUUID(), run2, 2, period, "5100-SALARY", run2Amount, occurredAt);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> f1 = pool.submit(post(barrier, run1Event));
      Future<Boolean> f2 = pool.submit(post(barrier, run2Event));

      // Both deliveries posted (each a first delivery of a distinct event id) — neither was
      // dropped.
      assertThat(f1.get()).as("run 1 posted").isTrue();
      assertThat(f2.get()).as("run 2 posted").isTrue();
    } finally {
      pool.shutdownNow();
    }

    // The period nets to run 2 ONLY — the double-count (run1 + run2) is prevented by the advisory
    // lock making the later run see-and-reverse (or self-supersede) the earlier.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense())
        .as("period nets to run 2 only — no double-count")
        .isEqualTo(Money.ofMinor(run2Amount, "IDR"));

    // The ledger shows the reversal: two PRIMARYs + exactly one REVERSAL (three rows), and run 1 is
    // SUPERSEDED. (Which run carries the contra depends on lock-win order; either way run 1 nets
    // zero and run 2 stands.)
    assertThat(ledgerCountAsAdmin()).as("two PRIMARYs + one REVERSAL").isEqualTo(3L);
    assertThat(reversalCountAsAdmin()).as("exactly one reversal").isEqualTo(1L);
    assertThat(runStateAsAdmin(run1)).as("run 1 superseded").isEqualTo("SUPERSEDED");
  }

  private Callable<Boolean> post(CyclicBarrier barrier, LaborCostAllocatedEvent event) {
    return () ->
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              barrier.await();
              return laborService.handle(event);
            });
  }

  private LaborCostAllocatedEvent event(
      UUID eventId,
      UUID runId,
      int runSeq,
      String period,
      String glAccount,
      long amountMinor,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        period,
        OUTLET,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        false,
        false,
        occurredAt);
  }

  private long ledgerCountAsAdmin() throws Exception {
    return longQueryAsAdmin("SELECT count(*) FROM ledger_posting");
  }

  private long reversalCountAsAdmin() throws Exception {
    return longQueryAsAdmin("SELECT count(*) FROM ledger_posting WHERE posting_role = 'REVERSAL'");
  }

  private String runStateAsAdmin(UUID runId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT state FROM payroll_run_ledger WHERE payroll_run_id = '" + runId + "'")) {
      rs.next();
      return rs.getString("state");
    }
  }

  private long longQueryAsAdmin(String sql) throws Exception {
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
