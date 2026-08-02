package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent.LiabilityBucket;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityService;
import id.co.nativeapp.finance.labor.service.PayrollSettlementWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
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

/**
 * CONCURRENCY proof for payroll settlement (ADR 0032, Track P phase P5 review W2 — a money-touching
 * command, mirrors {@code TaxFilingConcurrencyTest}/{@code AssetDisposalConcurrencyTest}'s rigor).
 * {@code PayrollSettlementWriter} takes NO advisory lock — unlike {@code TaxSettlementWriter} (a
 * one-shot status-flip guard) it relies purely on the DB UNIQUE constraints for serialization, so a
 * genuine two-thread race is the real proof.
 *
 * <p><strong>Two DIFFERENT keys racing the SAME {@code (run, kind)}.</strong> Both threads pass the
 * up-front replay-by-key probe (neither has committed yet) and both attempt to insert a {@code
 * payroll_settlement} row; {@code uq_payroll_settlement_once (company_id, payroll_run_ledger_id,
 * kind)} lets exactly one commit — the loser's {@code DataIntegrityViolationException} (Spring's
 * translation of the constraint violation, surfacing at the transactional commit) rolls its ENTIRE
 * transaction back, including its journal entry, so no orphan posting survives; {@code
 * PayrollLiabilityAdvice#handleConcurrentConflict} maps it to {@code 409} at the API edge.
 *
 * <p><strong>A raced SAME-key replay is a narrower, accepted residual (documented on {@code
 * PayrollSettlementWriter}'s class javadoc, not exercised here):</strong> two requests carrying the
 * IDENTICAL key, racing each other before either commits, both pass the replay probe and one loses
 * the {@code uq_payroll_settlement_idempotency_key} insert — a {@code 409} instead of the graceful
 * {@code 200} replay a sequential retry would see. Mirrors the {@code TaxFilingConcurrencyTest}
 * idiom: any {@code RuntimeException} from the loser is accepted without pinning its exact
 * subclass, since Spring's exception translation for a constraint violation surfacing at
 * transaction-commit time (not inside a repository method call) is an implementation detail.
 */
@SpringBootTest
class PayrollSettlementConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-eeeeeeeeee44";
  private static final String ACTOR = "settlement-race@integration.co.id";

  @Autowired private PayrollLiabilityService liabilityService;
  @Autowired private PayrollSettlementWriter settlementWriter;
  @Autowired private Clock clock;

  @Test
  void concurrentSettlesOfTheSameBucketWithDifferentKeysSettleExactlyOnce() throws Exception {
    UUID runId = UUID.randomUUID();
    String period = LedgerPosting.periodOf(clock.instant());
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            liabilityService.handle(
                new PayrollLiabilitiesPostedEvent(
                    UUID.randomUUID(),
                    TENANT,
                    runId,
                    1,
                    "REGULAR",
                    period,
                    "IDR",
                    Money.ofMinor(10_000_000L, "IDR"),
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))),
                    false,
                    clock.instant())));
    UUID runLedgerId = runLedgerIdAsAdmin(runId);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int settledCount = 0;
    int racedCount = 0;
    try {
      Future<Boolean> f1 = pool.submit(settleRace(barrier, runLedgerId, "race-key-1"));
      Future<Boolean> f2 = pool.submit(settleRace(barrier, runLedgerId, "race-key-2"));
      for (Future<Boolean> f : List.of(f1, f2)) {
        if (f.get()) {
          settledCount++;
        } else {
          racedCount++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly one winner, one loser — never both, never neither.
    assertThat(settledCount).isEqualTo(1);
    assertThat(racedCount).isEqualTo(1);

    // Exactly ONE payroll_settlement row for this (run, kind) — no double-settle.
    assertThat(
            countAsAdmin(
                "SELECT count(*) FROM payroll_settlement WHERE payroll_run_ledger_id = '"
                    + runLedgerId
                    + "' AND kind = 'NET_WAGES'"))
        .isEqualTo(1L);

    // Exactly TWO journal entries total: the liability accrual + the ONE successful settlement —
    // the loser's orphan entry rolled back with its transaction, never persisted.
    assertThat(countAsAdmin("SELECT count(*) FROM journal_entry")).isEqualTo(2L);
    // The clearing account (1900) was credited exactly once for the settled amount — not twice.
    assertThat(
            countAsAdmin(
                "SELECT COALESCE(SUM(credit_minor),0) FROM journal_line WHERE account_code ="
                    + " '1900'"))
        .isEqualTo(10_000_000L);
  }

  private Callable<Boolean> settleRace(CyclicBarrier barrier, UUID runLedgerId, String key) {
    return () ->
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              barrier.await();
              try {
                settlementWriter.settle(runLedgerId, SettlementKind.NET_WAGES, key);
                return true;
              } catch (RuntimeException raced) {
                // The loser: a DataIntegrityViolationException from uq_payroll_settlement_once,
                // translated at transaction-commit time (see the class javadoc).
                return false;
              }
            });
  }

  private UUID runLedgerIdAsAdmin(UUID payrollRunId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT id FROM payroll_run_ledger WHERE payroll_run_id = ?")) {
      ps.setObject(1, payrollRunId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
