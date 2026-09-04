package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * APPEND-ONLY supersession proof (#23): a corrected re-run is a NEW {@code run_seq} for the same
 * {@code (company, period)}. Finance must NOT double-count two runs. It reverses the prior run's
 * labor postings (append-only contra entries — the prior rows are never mutated) and reposts the
 * new run, so the consolidated P&L reflects ONLY the latest run. Re-delivery of the superseding run
 * never double-reverses (the deterministic synthetic reversal id + the UNIQUE backstop).
 */
@SpringBootTest
class LaborSupersessionTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PnlReader pnlReader;

  @Test
  void aHigherRunSeqReversesThePriorRunAndRepostsSoThePnlReflectsOnlyTheLatest() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    long run1Amount = 20_000_000L;
    long run2Amount = 22_000_000L; // the corrected run

    // RUN 1 (run_seq=1) lands.
    assertThat(
            laborService.handle(
                event(
                    UUID.randomUUID(),
                    run1,
                    1,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    run1Amount,
                    occurredAt)))
        .isTrue();
    ConsolidatedPnl afterRun1 =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(afterRun1.expense()).isEqualTo(Money.ofMinor(run1Amount, "IDR"));

    // RUN 2 (run_seq=2) supersedes run 1: finance reverses run 1 (contra) then posts run 2.
    assertThat(
            laborService.handle(
                event(
                    UUID.randomUUID(),
                    run2,
                    2,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    run2Amount,
                    occurredAt)))
        .isTrue();

    // The P&L now reflects ONLY run 2: run1 (+20M) + reversal (-20M) + run2 (+22M) = 22M.
    ConsolidatedPnl afterRun2 =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(afterRun2.expense()).isEqualTo(Money.ofMinor(run2Amount, "IDR"));

    // The audit trail: 3 ledger rows — run1 PRIMARY, the REVERSAL contra, run2 PRIMARY. The contra
    // is a negative-amount REVERSAL tagged with run 1's payroll_run_id (append-only, never a
    // delete).
    assertThat(ledgerCountAsAdmin()).isEqualTo(3L);
    assertThat(reversalCountAsAdmin()).isEqualTo(1L);
    assertThat(reversalAmountForRunAsAdmin(run1)).isEqualTo(-run1Amount);

    // The run-control rows: run 1 SUPERSEDED, run 2 still active (PENDING — not yet reconciled).
    assertThat(runStateAsAdmin(run1)).isEqualTo("SUPERSEDED");
    assertThat(runStateAsAdmin(run2)).isEqualTo("PENDING");
  }

  @Test
  void redeliveringTheSupersedingRunNeverDoubleReverses() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    long run1Amount = 20_000_000L;
    long run2Amount = 22_000_000L;

    laborService.handle(
        event(UUID.randomUUID(), run1, 1, period, OUTLET, "5100-SALARY", run1Amount, occurredAt));

    LaborCostAllocatedEvent run2Event =
        event(UUID.randomUUID(), run2, 2, period, OUTLET, "5100-SALARY", run2Amount, occurredAt);
    assertThat(laborService.handle(run2Event)).as("first delivery of run 2").isTrue();
    // Re-deliver run 2 (same event id): a clean no-op — claims nothing, reverses nothing.
    assertThat(laborService.handle(run2Event)).as("re-delivery of run 2").isFalse();

    // Still exactly 3 ledger rows and exactly ONE reversal — the prior run was not reversed twice.
    assertThat(ledgerCountAsAdmin()).isEqualTo(3L);
    assertThat(reversalCountAsAdmin()).isEqualTo(1L);

    // The net per period is exactly the highest active run's labor cost.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(run2Amount, "IDR"));
  }

  @Test
  void aLowerSeqRunArrivingAfterAHigherSeqRunNetsToZeroSoThePeriodNeverDoubleCounts()
      throws Exception {
    // Out-of-order delivery (#23, money correctness): run_seq=2 buckets are processed BEFORE
    // run_seq=1 buckets (different partitions / parallel consumers). The late run 1 must NOT leave
    // a
    // net residual — the period must net to run 2 ONLY, never run1 + run2.
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    long run1Amount = 20_000_000L;
    long run2Amount = 22_000_000L; // the corrected (higher-seq) run

    // RUN 2 (run_seq=2) lands FIRST — there is no prior to reverse yet.
    assertThat(
            laborService.handle(
                event(
                    UUID.randomUUID(),
                    run2,
                    2,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    run2Amount,
                    occurredAt)))
        .isTrue();
    ConsolidatedPnl afterRun2 =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(afterRun2.expense()).isEqualTo(Money.ofMinor(run2Amount, "IDR"));

    // RUN 1 (run_seq=1) arrives LATE. A higher-seq run is already active, so run 1 is already
    // superseded: it posts its PRIMARY then immediately its own compensating REVERSAL (net zero)
    // and
    // its run row flips to SUPERSEDED. The period stays at run 2 only — NO double-count.
    assertThat(
            laborService.handle(
                event(
                    UUID.randomUUID(),
                    run1,
                    1,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    run1Amount,
                    occurredAt)))
        .isTrue();

    ConsolidatedPnl afterLateRun1 =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(afterLateRun1.expense())
        .as("period nets to run 2 only — late run 1 contributes zero")
        .isEqualTo(Money.ofMinor(run2Amount, "IDR"));

    // Ledger audit: run2 PRIMARY (+22M), run1 PRIMARY (+20M), run1 self-REVERSAL (-20M) = 3 rows,
    // exactly one reversal, and run 1 nets zero on the books.
    assertThat(ledgerCountAsAdmin()).isEqualTo(3L);
    assertThat(reversalCountAsAdmin()).isEqualTo(1L);
    assertThat(reversalAmountForRunAsAdmin(run1)).isEqualTo(-run1Amount);

    // Run-control rows: run 1 SUPERSEDED on the spot, run 2 the active run (PENDING — not
    // reconciled
    // yet).
    assertThat(runStateAsAdmin(run1)).isEqualTo("SUPERSEDED");
    assertThat(runStateAsAdmin(run2)).isEqualTo("PENDING");
  }

  @Test
  void redeliveringTheLateLowerSeqRunNeverDoubleReverses() throws Exception {
    // The out-of-order self-supersession is idempotent: re-delivering the late lower-seq bucket is
    // a
    // clean no-op (the deterministic synthetic reversal id + the UNIQUE backstop).
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    laborService.handle(
        event(UUID.randomUUID(), run2, 2, period, OUTLET, "5100-SALARY", 22_000_000L, occurredAt));

    LaborCostAllocatedEvent lateRun1 =
        event(UUID.randomUUID(), run1, 1, period, OUTLET, "5100-SALARY", 20_000_000L, occurredAt);
    assertThat(laborService.handle(lateRun1)).as("first delivery of late run 1").isTrue();
    assertThat(laborService.handle(lateRun1)).as("re-delivery of late run 1").isFalse();

    assertThat(ledgerCountAsAdmin()).isEqualTo(3L);
    assertThat(reversalCountAsAdmin()).isEqualTo(1L);
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(22_000_000L, "IDR"));
  }

  @Test
  void multiBucketRunsInterleavedNetToTheHigherRunOnlyWhenALateBucketArrivesAfterSupersession()
      throws Exception {
    // MULTI-BUCKET supersession ordering (#23): run 1 emits TWO buckets (B1a, B1b); run 2 emits one
    // (B2). They arrive interleaved as B1a -> B2 -> B1b. When B2 lands it reverses the run-1
    // buckets
    // present THEN (only B1a), flipping run 1 to SUPERSEDED. The LATE B1b then arrives for an
    // already-superseded run and must SELF-SUPERSEDE to zero (post its PRIMARY then its own
    // compensating REVERSAL) — otherwise B1b would be a net residual and the period would
    // double-count. The period must net to run 2 ONLY.
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    long b1a = 12_000_000L;
    long b1b = 8_000_000L; // run 1 total 20M across two buckets
    long b2 = 22_000_000L; // the corrected (higher-seq) run, one bucket

    // B1a (run 1) lands.
    assertThat(
            laborService.handle(
                event(UUID.randomUUID(), run1, 1, period, OUTLET, "5100-SALARY", b1a, occurredAt)))
        .isTrue();

    // B2 (run 2) lands next: it supersedes run 1, reversing the run-1 buckets present now (B1a).
    assertThat(
            laborService.handle(
                event(UUID.randomUUID(), run2, 2, period, OUTLET, "5100-SALARY", b2, occurredAt)))
        .isTrue();
    assertThat(runStateAsAdmin(run1)).isEqualTo("SUPERSEDED");

    // B1b (run 1) arrives LATE — run 1 is already SUPERSEDED. It must self-supersede to zero.
    assertThat(
            laborService.handle(
                event(UUID.randomUUID(), run1, 1, period, OUTLET, "5100-SALARY", b1b, occurredAt)))
        .isTrue();

    // The period nets to run 2 ONLY: B1a(+12M) + reversal(-12M) + B2(+22M) + B1b(+8M) + B1b
    // self-reversal(-8M) = 22M. The late B1b contributes zero.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(pnl.expense())
        .as("period nets to run 2 only — the late B1b self-supersedes to zero")
        .isEqualTo(Money.ofMinor(b2, "IDR"));

    // Audit: 5 ledger rows (B1a, B1a-reversal, B2, B1b, B1b-self-reversal); run 1 still SUPERSEDED.
    assertThat(ledgerCountAsAdmin()).isEqualTo(5L);
    assertThat(reversalCountAsAdmin()).isEqualTo(2L);
    assertThat(runStateAsAdmin(run1)).isEqualTo("SUPERSEDED");
    assertThat(runStateAsAdmin(run2)).isEqualTo("PENDING");
  }

  // ----------------------------------------------------------------------- helpers

  private LaborCostAllocatedEvent event(
      UUID eventId,
      UUID runId,
      int runSeq,
      String period,
      UUID outlet,
      String glAccount,
      long amountMinor,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        "REGULAR",
        period,
        outlet,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        false,
        false,
        occurredAt);
  }

  private long reversalCountAsAdmin() throws Exception {
    return longQueryAsAdmin("SELECT count(*) FROM ledger_posting WHERE posting_role = 'REVERSAL'");
  }

  private long reversalAmountForRunAsAdmin(UUID runId) throws Exception {
    return longQueryAsAdmin(
        "SELECT amount_minor FROM ledger_posting WHERE posting_role = 'REVERSAL'"
            + " AND payroll_run_id = '"
            + runId
            + "'");
  }

  /** Single-{@code long}-column admin (BYPASSRLS) query, local to this test's reversal helpers. */
  private long longQueryAsAdmin(String sql) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
