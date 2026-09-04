package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomicity (rule 3, ENGINEERING-STANDARDS §3.2 / HR-3) for the GL's money path: the journal entry,
 * its lines and the {@code JournalEntryPosted} outbox row commit together or not at all — the
 * finance analogue of restaurant's {@code RecordSaleAtomicityTest}.
 *
 * <p>{@code GeneralLedgerWriter.post} is {@code MANDATORY}-propagation, so the test drives it
 * through a test-only {@code @Transactional} harness bean that calls {@code post} and then throws
 * INSIDE the same transaction — i.e. after the entry, lines AND outbox row have all been written.
 * The expectation: the failure rolls the whole transaction back and NONE of the three tables keeps
 * a row. Counted over the admin (BYPASSRLS) connection because the tables are FORCE RLS. The {@code
 * RlsAutoApplyAspect} binds the tenant GUC on any {@code @Transactional} bean method, so the
 * harness posts under a properly bound tenant — a failure BEFORE the boom (e.g. RLS rejecting the
 * write) would fail the exception-message assertion, so this test cannot pass vacuously.
 */
@SpringBootTest
class GlPostingAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  static final String BOOM = "forced failure after the outbox write (test harness)";

  @Autowired private GlAtomicityHarness harness;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackEntryLinesAndOutboxTogether() throws Exception {
    UUID entryId = UUID.randomUUID();
    JournalEntry entry =
        JournalEntry.balanced(
            entryId,
            "2026-09",
            Instant.parse("2026-09-02T03:00:00Z"),
            "atomicity probe",
            "IDR",
            UUID.randomUUID(),
            false,
            List.of(
                JournalLine.debit(entryId, 1, "1100", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "4000", Money.ofMinor(250_000L, "IDR"))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, "tester", () -> harness.postThenBoom(entry, TENANT)))
        .hasMessageContaining(BOOM);

    // All three writes rolled back together: no entry, no lines, no outbox event.
    assertThat(rowCountAsAdmin("journal_entry")).isZero();
    assertThat(rowCountAsAdmin("journal_line")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Opens the transaction {@code post} (MANDATORY) joins, lets the full write sequence — entry,
   * lines, outbox row — complete, then throws while still inside it. Declared as a bean by {@link
   * HarnessConfig}; being {@code @Transactional}, it both opens the transaction and gets the tenant
   * GUC bound by {@code RlsAutoApplyAspect}, exactly like a production {@code *Writer}.
   */
  static class GlAtomicityHarness {
    private final GeneralLedgerWriter generalLedgerWriter;

    GlAtomicityHarness(GeneralLedgerWriter generalLedgerWriter) {
      this.generalLedgerWriter = generalLedgerWriter;
    }

    @Transactional
    public Void postThenBoom(JournalEntry entry, String companyId) {
      generalLedgerWriter.post(entry, companyId);
      throw new IllegalStateException(BOOM);
    }
  }

  /** Distinct context configuration — the harness bean never leaks into other test classes. */
  @TestConfiguration
  static class HarnessConfig {
    @Bean
    GlAtomicityHarness glAtomicityHarness(GeneralLedgerWriter generalLedgerWriter) {
      return new GlAtomicityHarness(generalLedgerWriter);
    }
  }
}
