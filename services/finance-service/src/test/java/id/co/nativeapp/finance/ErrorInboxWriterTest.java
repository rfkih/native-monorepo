package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers integration test for {@link ErrorInboxWriter}.
 *
 * <p>Uses the shared {@link PostgresRlsTestBase} container (Flyway applies V14 which creates {@code
 * error_log}). Each test truncates {@code error_log} over the admin connection so tests are fully
 * isolated (the {@code resetTables()} in the base does NOT truncate {@code error_log}, so we do it
 * ourselves here — as documented in the task spec).
 *
 * <p>Proven behaviours:
 *
 * <ul>
 *   <li>Recording the same {@code (ex, source)} twice yields ONE row with {@code occurrence_count
 *       == 2}; the second call returns {@code 2}.
 *   <li>A message containing a NIK / bank account is stored redacted.
 *   <li>Two messages differing only in embedded numbers deduplicate to one row (the fingerprint
 *       normalises digit runs).
 *   <li>On a repeat of the same fingerprint, {@code trace_id} is overwritten to the LATEST
 *       occurrence's value (last-write-wins, matching {@code redacted_message}) — the
 *       traceable-error-reference feature's basis for {@code SELECT * FROM error_log WHERE trace_id
 *       = '<reference>'}.
 * </ul>
 */
@SpringBootTest
class ErrorInboxWriterTest extends PostgresRlsTestBase {

  @Autowired private ErrorInboxWriter errorInboxWriter;

  @BeforeEach
  void truncateErrorLog() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE error_log");
    }
  }

  // ------------------------------------------------------------------ dedup

  @Test
  void recordingTheSameExceptionTwiceYieldsOneRowWithCount2() throws Exception {
    RuntimeException ex = new RuntimeException("connection refused");
    String source = "kafka:SaleRecorded";

    long first = errorInboxWriter.record(ex, source, null, null).occurrenceCount();
    long second = errorInboxWriter.record(ex, source, null, null).occurrenceCount();

    assertThat(first).isEqualTo(1L);
    assertThat(second).isEqualTo(2L);

    assertThat(errorLogRowCount()).isEqualTo(1L);
    assertThat(errorLogOccurrenceCount(source)).isEqualTo(2L);
  }

  // ------------------------------------------------------------------ trace-id last-write-wins

  @Test
  void traceIdIsUpdatedToTheLatestOccurrenceOnConflict() throws Exception {
    // Same (ex, source) so both calls upsert the same fingerprint row.
    RuntimeException ex = new RuntimeException("connection refused");
    String source = "kafka:SaleRecorded";

    errorInboxWriter.record(ex, source, null, "trace-first");
    errorInboxWriter.record(ex, source, null, "trace-second");

    assertThat(errorLogRowCount()).isEqualTo(1L);
    assertThat(traceIdAsAdmin(source)).isEqualTo("trace-second");
  }

  // ------------------------------------------------------------------ PII redaction

  @Test
  void messageContainingNikIsStoredRedacted() throws Exception {
    // NIK is 16 digits — must be masked in redacted_message
    String nikMessage = "Employee NIK 3201234567890001 failed validation";
    RuntimeException ex = new RuntimeException(nikMessage);
    String source = "kafka:PayrollPosted";

    errorInboxWriter.record(ex, source, null, null);

    String stored = redactedMessageAsAdmin(source);
    assertThat(stored).doesNotContain("3201234567890001");
    assertThat(stored).contains("***");
  }

  @Test
  void messageContainingBankAccountIsStoredRedacted() throws Exception {
    // 10-digit bank account number — at the threshold for masking
    String bankMessage = "Bank account 1234567890 not found";
    RuntimeException ex = new RuntimeException(bankMessage);
    String source = "kafka:ExpenseRecorded";

    errorInboxWriter.record(ex, source, null, null);

    String stored = redactedMessageAsAdmin(source);
    assertThat(stored).doesNotContain("1234567890");
    assertThat(stored).contains("***");
  }

  // ------------------------------------------------------------------ number-normalised dedup

  @Test
  void messagesWithDifferentEmbeddedNumbersDedupToOneRow() throws Exception {
    // These two exceptions differ only in the number — fingerprintNormalize collapses digits,
    // so both map to the same fingerprint and upsert to ONE row.
    RuntimeException ex1 = new RuntimeException("Timeout after 3000ms on attempt 1");
    RuntimeException ex2 = new RuntimeException("Timeout after 5000ms on attempt 2");
    String source = "kafka:LaborCostAllocated";

    long c1 = errorInboxWriter.record(ex1, source, null, null).occurrenceCount();
    long c2 = errorInboxWriter.record(ex2, source, null, null).occurrenceCount();

    assertThat(c1).isEqualTo(1L);
    assertThat(c2).isEqualTo(2L);
    assertThat(errorLogRowCount()).isEqualTo(1L);
  }

  // ------------------------------------------------------------------ admin helpers

  private long errorLogRowCount() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM error_log")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long errorLogOccurrenceCount(String source) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT occurrence_count FROM error_log WHERE source = ?")) {
      ps.setString(1, source);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0L;
      }
    }
  }

  private String traceIdAsAdmin(String source) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT trace_id FROM error_log WHERE source = ?")) {
      ps.setString(1, source);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
        return null;
      }
    }
  }

  private String redactedMessageAsAdmin(String source) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT redacted_message FROM error_log WHERE source = ?")) {
      ps.setString(1, source);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
        return null;
      }
    }
  }
}
