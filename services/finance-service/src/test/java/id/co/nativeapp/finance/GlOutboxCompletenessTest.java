package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.gl.messaging.JournalEntryPostedSchema;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0071 P1 — the numeric belt to the ArchUnit suspenders ({@code
 * onlyTheGeneralLedgerWriterPersistsJournals}): every {@code journal_entry} row has EXACTLY ONE
 * {@code JournalEntryPosted} outbox row, emitted atomically with it, and the wire payload is
 * faithful to the persisted rows (same period/currency/posting role, same lines, and balanced).
 *
 * <p>Drives two INDEPENDENT real posting flows (revenue and expense — different writers, both
 * funnelling through {@code GeneralLedgerWriter.post}) against the real Flyway migration + RLS
 * under the unprivileged {@code app_user}, then reconciles the {@code journal_entry} table against
 * the outbox over the admin (BYPASSRLS) connection. Because the emit lives in the single
 * persistence door, this holds for every posting writer without naming any of them — which is the
 * point.
 */
@SpringBootTest
class GlOutboxCompletenessTest extends PostgresRlsTestBase {

  private static final Instant OCCURRED = Instant.parse("2026-09-02T03:00:00Z");

  @Autowired private RevenuePostingService revenueService;
  @Autowired private ExpensePostingService expenseService;

  @Test
  void everyPersistedJournalEntryEmitsExactlyOneFaithfulJournalEntryPostedEvent() throws Exception {
    UUID company = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            OCCURRED));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "cogs",
            OCCURRED));

    List<EntryRow> entries = entryRows(company);
    assertThat(entries).isNotEmpty();

    List<GenericRecord> events = new ArrayList<>();
    for (GenericRecord event :
        decodeOutbox(JournalEntryPostedSchema.EVENT_TYPE, JournalEntryPostedSchema.schema())) {
      if (event.get("company_id").toString().equals(company.toString())) {
        events.add(event);
      }
    }

    // Completeness: one event per entry, no extras, no dupes.
    assertThat(events).hasSize(entries.size());
    Map<String, GenericRecord> byEntryId = new HashMap<>();
    for (GenericRecord event : events) {
      assertThat(byEntryId.put(event.get("journal_entry_id").toString(), event))
          .as("duplicate JournalEntryPosted for one journal_entry")
          .isNull();
    }

    // Faithfulness: the wire payload matches the persisted rows, line for line, and balances.
    for (EntryRow entry : entries) {
      GenericRecord event = byEntryId.get(entry.id());
      assertThat(event)
          .as("missing JournalEntryPosted for journal_entry %s", entry.id())
          .isNotNull();
      assertThat(event.get("period").toString()).isEqualTo(entry.period());
      assertThat(event.get("currency").toString()).isEqualTo(entry.currency());
      assertThat(event.get("posting_role").toString()).isEqualTo(entry.postingRole());
      assertThat(event.get("source_event_id").toString()).isEqualTo(entry.sourceEventId());
      assertThat(event.get("business_id")).isNull(); // until ADR 0071 P5

      @SuppressWarnings("unchecked")
      List<GenericRecord> lines = new ArrayList<>((List<GenericRecord>) event.get("lines"));
      lines.sort(java.util.Comparator.comparingInt(l -> (Integer) l.get("line_no")));
      List<LineRow> persisted = lineRows(entry.id());
      assertThat(lines).hasSize(persisted.size());
      long debits = 0;
      long credits = 0;
      for (int i = 0; i < persisted.size(); i++) {
        GenericRecord line = lines.get(i);
        LineRow row = persisted.get(i);
        assertThat(line.get("line_no")).isEqualTo(row.lineNo());
        assertThat(line.get("account_code").toString()).isEqualTo(row.accountCode());
        assertThat(line.get("debit_minor")).isEqualTo(row.debitMinor());
        assertThat(line.get("credit_minor")).isEqualTo(row.creditMinor());
        assertThat(line.get("currency").toString()).isEqualTo(entry.currency());
        debits += row.debitMinor();
        credits += row.creditMinor();
      }
      assertThat(debits).as("wire lines balance").isEqualTo(credits).isGreaterThan(0);
    }
  }

  private record EntryRow(
      String id, String period, String currency, String postingRole, String sourceEventId) {}

  private record LineRow(int lineNo, String accountCode, long debitMinor, long creditMinor) {}

  private List<EntryRow> entryRows(UUID company) throws Exception {
    List<EntryRow> rows = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, period, currency, posting_role, source_event_id FROM journal_entry"
                    + " WHERE company_id = '"
                    + company
                    + "' ORDER BY created_at, id")) {
      while (rs.next()) {
        rows.add(
            new EntryRow(
                rs.getString("id"),
                rs.getString("period"),
                rs.getString("currency"),
                rs.getString("posting_role"),
                rs.getString("source_event_id")));
      }
    }
    return rows;
  }

  private List<LineRow> lineRows(String entryId) throws Exception {
    List<LineRow> rows = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT line_no, account_code, debit_minor, credit_minor FROM journal_line"
                    + " WHERE entry_id = '"
                    + entryId
                    + "' ORDER BY line_no")) {
      while (rs.next()) {
        rows.add(
            new LineRow(
                rs.getInt("line_no"),
                rs.getString("account_code"),
                rs.getLong("debit_minor"),
                rs.getLong("credit_minor")));
      }
    }
    return rows;
  }

  private List<GenericRecord> decodeOutbox(String eventType, org.apache.avro.Schema schema)
      throws Exception {
    List<GenericRecord> out = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT payload FROM outbox WHERE event_type = '"
                    + eventType
                    + "' ORDER BY occurred_at, id")) {
      while (rs.next()) {
        out.add(AvroSerde.deserialize(rs.getBytes("payload"), schema));
      }
    }
    return out;
  }

  private static Connection adminConnection() throws java.sql.SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
