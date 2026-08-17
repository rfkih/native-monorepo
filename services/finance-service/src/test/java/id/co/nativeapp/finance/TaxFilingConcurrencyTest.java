package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.tax.service.FileReturnResult;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CONCURRENCY proof for the money-critical tax commands (§3.2 — atomicity + idempotency under
 * concurrency). Two threads, released together by a {@link CyclicBarrier}, race the SAME command:
 *
 * <ul>
 *   <li><strong>file()</strong> — the {@code pg_advisory_xact_lock} + {@code uq_tax_filing} UNIQUE
 *       serialize the two: exactly one tax_filing row exists, exactly one call reports {@code
 *       created == true}, and {@code VAT_PAYABLE (2300)} is credited exactly once (the net, not
 *       double).
 *   <li><strong>settle()</strong> — the up-front status guard + the {@code @Version} optimistic
 *       lock let exactly one settlement post; the loser rolls back (its orphan journal entry with
 *       it), so {@code 2300} is debited exactly once and the filing ends SETTLED.
 * </ul>
 */
@SpringBootTest
class TaxFilingConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-cccccccccc33";
  private static final String ACTOR = "tax-race@integration.co.id";

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private TaxFilingService taxFilingService;
  @Autowired private Clock clock;

  /** Seeds output VAT 1,100,000 + input VAT 440,000 (net 660,000 PAYABLE) for the period. */
  private void seedVat() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID invoice =
              invoiceWriter.createDraft(
                  customerWriter.create("Acme Sales", null, null).id(),
                  "IDR",
                  true,
                  List.of(new InvoiceLineInput("Sale", 1, 10_000_000L)));
          invoiceWriter.issue(invoice, 30);
          UUID bill =
              billWriter.createDraft(
                  vendorWriter.create("Acme Supplies", null, null).id(),
                  "IDR",
                  true,
                  List.of(new BillLineInput("Purchase", 1, 4_000_000L, false)));
          billWriter.post(bill, 30);
          return null;
        });
  }

  @Test
  void concurrentFileFilesExactlyOnce() throws Exception {
    seedVat();
    String period = LedgerPosting.periodOf(clock.instant());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    FileReturnResult r1;
    FileReturnResult r2;
    try {
      Future<FileReturnResult> f1 = pool.submit(fileRace(barrier, period));
      Future<FileReturnResult> f2 = pool.submit(fileRace(barrier, period));
      r1 = f1.get();
      r2 = f2.get();
    } finally {
      pool.shutdownNow();
    }

    // Both calls returned the SAME filing id; exactly one freshly filed it (created == true).
    assertThat(r1.filingId()).isEqualTo(r2.filingId());
    assertThat(r1.created() ^ r2.created()).as("exactly one call created the filing").isTrue();

    // Exactly ONE tax_filing row, and VAT_PAYABLE (2300) credited exactly once (the net, not
    // double).
    assertThat(countAsAdmin("SELECT count(*) FROM tax_filing")).isEqualTo(1L);
    assertThat(
            sumAsAdmin(
                "SELECT COALESCE(SUM(credit_minor),0) FROM journal_line WHERE account_code='2300'"))
        .isEqualTo(660_000L);
  }

  @Test
  void concurrentSettleSettlesExactlyOnce() throws Exception {
    seedVat();
    String period = LedgerPosting.periodOf(clock.instant());
    UUID filingId =
        TenantContext.callAs(TENANT, ACTOR, () -> taxFilingService.file(period).filingId());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    boolean ok1;
    boolean ok2;
    try {
      Future<Boolean> f1 = pool.submit(settleRace(barrier, filingId));
      Future<Boolean> f2 = pool.submit(settleRace(barrier, filingId));
      ok1 = f1.get();
      ok2 = f2.get();
    } finally {
      pool.shutdownNow();
    }

    // Exactly one settle succeeded; the loser rolled back (status guard or optimistic lock).
    assertThat(ok1 ^ ok2).as("exactly one settle succeeded").isTrue();
    // VAT_PAYABLE (2300) debited exactly once (the loser's orphan settlement entry rolled back).
    assertThat(
            sumAsAdmin(
                "SELECT COALESCE(SUM(debit_minor),0) FROM journal_line WHERE account_code='2300'"))
        .isEqualTo(660_000L);
  }

  private java.util.concurrent.Callable<FileReturnResult> fileRace(
      CyclicBarrier barrier, String period) {
    return () ->
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              barrier.await();
              return taxFilingService.file(period);
            });
  }

  private java.util.concurrent.Callable<Boolean> settleRace(CyclicBarrier barrier, UUID filingId) {
    return () ->
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              barrier.await();
              try {
                taxFilingService.settle(filingId);
                return true;
              } catch (RuntimeException raced) {
                // The loser: TaxFilingStateException (already settled) or an optimistic-lock
                // failure.
                return false;
              }
            });
  }

  private long countAsAdmin(String sql) throws Exception {
    return sumAsAdmin(sql);
  }

  private long sumAsAdmin(String sql) throws Exception {
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
