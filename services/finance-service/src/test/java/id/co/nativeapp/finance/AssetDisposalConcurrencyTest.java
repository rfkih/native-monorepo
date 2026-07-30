package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.assets.domain.AssetAlreadyDisposedException;
import id.co.nativeapp.finance.assets.domain.AssetDepreciationBehindException;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetDisposalWriter;
import id.co.nativeapp.finance.assets.service.DisposeAssetResult;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * CONCURRENCY proof for asset disposal (ADR 0022, money-touching command): (a) two threads racing
 * {@code dispose} on the SAME asset with DIFFERENT keys — the shared {@code company:ASSET_POSTING}
 * advisory lock serializes them, exactly one disposal entry posts, the loser gets the one-shot 409;
 * (b) a dispose racing the amortization run — the shared lock forces one of the two consistent
 * serial orders (disposed-then-run-excludes, or ran-then-dispose-rejected-overshoot), never the
 * torn state where the run posts depreciation the frozen disposal amounts miss.
 */
@SpringBootTest
class AssetDisposalConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-dddddddddd99";
  private static final String ACTOR = "disposal-race@integration.co.id";

  @Autowired private FixedAssetWriter fixedAssetWriter;
  @Autowired private AssetDisposalWriter assetDisposalWriter;
  @Autowired private AmortizationRunWriter amortizationRunWriter;
  @Autowired private Clock clock;

  @Test
  void concurrentDisposesPostExactlyOnce() throws Exception {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    UUID assetId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                fixedAssetWriter
                    .acquire("Racy asset", today, 12_000_000L, 0L, 12, "IDR", "race-acq-1")
                    .assetId());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int disposedCount = 0;
    int conflictCount = 0;
    try {
      Future<DisposeAssetResult> f1 = pool.submit(dispose(barrier, assetId, "race-key-1"));
      Future<DisposeAssetResult> f2 = pool.submit(dispose(barrier, assetId, "race-key-2"));
      for (Future<DisposeAssetResult> f : java.util.List.of(f1, f2)) {
        try {
          assertThat(f.get().created()).isTrue();
          disposedCount++;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(AssetAlreadyDisposedException.class);
          conflictCount++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly one winner, one one-shot conflict — and exactly ONE disposal entry in the ledger.
    assertThat(disposedCount).isEqualTo(1);
    assertThat(conflictCount).isEqualTo(1);
    assertThat(
            countAsAdmin(
                "SELECT count(*) FROM journal_entry WHERE description = 'Fixed asset disposed'"))
        .isEqualTo(1L);
    assertThat(countAsAdmin("SELECT count(*) FROM fixed_asset WHERE status = 'DISPOSED'"))
        .isEqualTo(1L);
  }

  @Test
  void aDisposeRacingTheRunNeverTearsTheFrozenAmounts() throws Exception {
    String period = LedgerPosting.periodOf(clock.instant());
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    // Acquired two months ago → month 1 posted (catch-up satisfied), month 2 due THIS period: the
    // run wants to post it while the dispose wants to freeze without it.
    UUID assetId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                fixedAssetWriter
                    .acquire(
                        "Contended asset",
                        today.minusMonths(2),
                        12_000_000L,
                        0L,
                        12,
                        "IDR",
                        "race-acq-2")
                    .assetId());
    String priorPeriod = java.time.YearMonth.parse(period).minusMonths(1).toString();
    TenantContext.callAs(TENANT, ACTOR, () -> amortizationRunWriter.run(priorPeriod));

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    boolean disposedWon;
    try {
      Future<DisposeAssetResult> disposeF = pool.submit(dispose(barrier, assetId, "race-key-3"));
      Future<?> runF =
          pool.submit(
              (Callable<Object>)
                  () ->
                      TenantContext.callAs(
                          TENANT,
                          ACTOR,
                          () -> {
                            barrier.await();
                            return amortizationRunWriter.run(period);
                          }));
      runF.get(); // the run itself always succeeds (it may or may not include the asset)
      try {
        assertThat(disposeF.get().created()).isTrue();
        disposedWon = true;
      } catch (ExecutionException e) {
        // The run got there first and posted this period's depreciation → overshoot 409.
        assertThat(e.getCause()).isInstanceOf(AssetDepreciationBehindException.class);
        disposedWon = false;
      }
    } finally {
      pool.shutdownNow();
    }

    // Whichever order the shared lock chose, the ledger and the frozen amounts must agree.
    long linesThisPeriod =
        countAsAdmin(
            "SELECT count(*) FROM amortization_run_line arl "
                + "JOIN amortization_run ar ON ar.id = arl.run_id "
                + "WHERE arl.item_id = '"
                + assetId
                + "' AND ar.period = '"
                + period
                + "'");
    if (disposedWon) {
      // The run must have EXCLUDED the disposed asset — and the frozen accumulated equals the
      // posted run-line sum exactly.
      assertThat(linesThisPeriod).isZero();
      long frozen =
          countAsAdmin(
              "SELECT accumulated_disposed_minor FROM fixed_asset WHERE id = '" + assetId + "'");
      long posted =
          countAsAdmin(
              "SELECT COALESCE(SUM(amount_minor),0) FROM amortization_run_line "
                  + "WHERE item_id = '"
                  + assetId
                  + "'");
      assertThat(frozen).isEqualTo(posted);
    } else {
      // The run won: the asset stayed ACTIVE with this period's depreciation posted.
      assertThat(linesThisPeriod).isEqualTo(1L);
      assertThat(
              countAsAdmin(
                  "SELECT count(*) FROM fixed_asset WHERE id = '"
                      + assetId
                      + "' AND status = 'ACTIVE'"))
          .isEqualTo(1L);
    }
  }

  private Callable<DisposeAssetResult> dispose(CyclicBarrier barrier, UUID assetId, String key) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return () ->
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              barrier.await();
              return assetDisposalWriter.dispose(assetId, today, 10_000_000L, "IDR", key);
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
