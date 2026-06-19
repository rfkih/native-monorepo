package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers test: re-delivering the SAME {@code SaleRecorded} event (same event id) must
 * produce exactly ONE balanced {@code journal_entry} — the GL idempotency proof.
 *
 * <p>Mirrors {@code SaleRecordedIdempotencyTest}: publishes twice with the same event id, publishes
 * a marker event, and awaits the marker's ledger posting before asserting.
 */
@SpringBootTest
class GlAutoPostingIdempotencyTest extends KafkaPostgresTestBase {

  private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final UUID BUSINESS = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Test
  void duplicateSaleRecordedProducesExactlyOneJournalEntry() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T10:00:00Z");
    long amountMinor = 2_000_000L;

    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        SaleRecordedFixtures.record(saleId, TENANT, BUSINESS, amountMinor, "IDR", occurredAt);

    // Deliver the SAME event id twice.
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), saleId.toString(), eventId, event);
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), saleId.toString(), eventId, event);

    // Marker event to confirm the duplicate has drained.
    UUID markerSaleId = UUID.randomUUID();
    UUID markerEventId = UUID.randomUUID();
    GenericRecord marker =
        SaleRecordedFixtures.record(markerSaleId, TENANT, BUSINESS, 1L, "IDR", occurredAt);
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), saleId.toString(), markerEventId, marker);

    // Wait for exactly 2 ledger rows: original + marker.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(2L));

    // The duplicate source_event_id must appear exactly ONCE in journal_entry.
    long journalCount = journalEntryCountForEventAsAdmin(eventId);
    assertThat(journalCount)
        .as("journal_entry must have exactly one row for the duplicated event id")
        .isEqualTo(1L);

    // The single journal entry must be balanced.
    assertJournalEntryIsBalancedAsAdmin(eventId);
  }

  private long journalEntryCountForEventAsAdmin(UUID eventId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM journal_entry WHERE source_event_id = ?")) {
      ps.setObject(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void assertJournalEntryIsBalancedAsAdmin(UUID eventId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                """
                SELECT SUM(jl.debit_minor) AS total_debit,
                       SUM(jl.credit_minor) AS total_credit
                  FROM journal_line jl
                  JOIN journal_entry je ON je.id = jl.entry_id
                 WHERE je.source_event_id = ?
                """)) {
      ps.setObject(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long totalDebit = rs.getLong("total_debit");
        long totalCredit = rs.getLong("total_credit");
        assertThat(totalDebit)
            .as("Σdebit must equal Σcredit (balanced journal)")
            .isEqualTo(totalCredit);
        assertThat(totalDebit).as("Σdebit must be strictly positive").isPositive();
      }
    }
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " payroll_run_ledger, group_ref, group_member, group_lead,"
              + " group_trial_balance, consolidation_ledger, consolidation_summary,"
              + " intercompany_match, group_membership_pending, processed_event,"
              + " outbox, within_company_close, member_group_index,"
              + " journal_line, journal_entry CASCADE");
    } catch (Exception ignored) {
      // Tables not yet created — nothing to reset.
    }
  }
}
