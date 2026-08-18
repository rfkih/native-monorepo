package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.inventory.messaging.SaleCogsRecordedEvent;
import id.co.nativeapp.finance.inventory.service.SaleCogsRecordedService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0067 Phase C (finance consumer side, §1, §5) — {@link SaleCogsRecordedService}/{@code
 * SaleCogsRecordedWriter} end-to-end against a REAL Testcontainers Postgres, the {@code
 * StockReceivedWriterTest} pattern. Proves the load-bearing DORMANT invariant: a company with NO
 * {@code inventory_method_config} row (every tenant today) is a claimed no-op, while a
 * TEST-ACTIVATED tenant (a row inserted directly by this test — no production activation path
 * exists yet) posts {@code Dr 5100 COGS / Cr 1100 Inventory} PLUS a dimensional {@code
 * ledger_posting(EXPENSE, 5100)} row for the per-outlet rollup — and writes NOTHING to {@code
 * consolidated_pnl} (ADR 0065: the dashboard reads the GL directly, so there is no accumulator to
 * remember). A re-delivered event posts exactly once (idempotency).
 */
@SpringBootTest
class SaleCogsRecordedWriterTest extends PostgresRlsTestBase {

  private static final String ACTIVE_TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
  private static final String INACTIVE_TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccc2";
  private static final UUID BUSINESS_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Autowired private SaleCogsRecordedService service;

  @Test
  void anActivatedTenantPostsCogsDebitAgainstInventoryCredit() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT);

    boolean posted = service.handle(event);
    assertThat(posted).isTrue();

    Map<String, Long> debit = accountAmountsAsAdmin(event.eventId(), "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(event.eventId(), "credit_minor");

    assertThat(debit)
        .as("Dr 5100 COGS for the exact fold")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5100", 32_500L));
    assertThat(credit)
        .as("Cr 1100 Inventory for the exact fold")
        .containsExactlyInAnyOrderEntriesOf(Map.of("1100", 32_500L));
  }

  @Test
  void anActivatedTenantAlsoWritesTheDimensionalLedgerPostingButNeverConsolidatedPnl()
      throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT);

    service.handle(event);

    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT business_id, posting_type, gl_account_code, amount_minor, currency"
                    + " FROM ledger_posting WHERE source_event_id = ?")) {
      ps.setObject(1, event.eventId());
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("the dimensional ledger_posting row must exist").isTrue();
        assertThat(rs.getObject("business_id", UUID.class)).isEqualTo(BUSINESS_ID);
        assertThat(rs.getString("posting_type")).isEqualTo("EXPENSE");
        assertThat(rs.getString("gl_account_code")).isEqualTo("5100");
        assertThat(rs.getLong("amount_minor")).isEqualTo(32_500L);
        assertThat(rs.getString("currency").strip()).isEqualTo("IDR");
      }
    }

    // ADR 0065: the dashboard P&L is GL-derived — writing to consolidated_pnl here would be the
    // exact "forgot to feed the accumulator" anti-pattern it closed. Assert it stays untouched.
    assertThat(rowCountAsAdmin("consolidated_pnl")).isZero();
  }

  @Test
  void aNonActivatedTenantIsAClaimedNoOp() throws Exception {
    // No inventory_method_config row for INACTIVE_TENANT at all — the DORMANT state every
    // production tenant is in today.
    SaleCogsRecordedEvent event = event(INACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).as("the event is still claimed, even though nothing posts").isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(processedEventExistsAsAdmin(event.eventId())).isTrue();
    assertThat(rowCountAsAdmin("ledger_posting")).isZero();
  }

  @Test
  void anExplicitlyInactiveConfigRowIsAlsoAClaimedNoOp() throws Exception {
    activatePerpetualInventory(INACTIVE_TENANT, "2026-01", false);
    SaleCogsRecordedEvent event = event(INACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aFutureCutoverIsAlsoInactiveForAnEarlierPeriod() throws Exception {
    // cutover 2027-01 is AFTER the event's 2026-08 period — not yet active for this sale.
    activatePerpetualInventory(ACTIVE_TENANT, "2027-01");
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aRedeliveredEventPostsExactlyOnce() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT);

    boolean first = service.handle(event);
    boolean second = service.handle(event);

    assertThat(first).isTrue();
    assertThat(second).as("a re-delivery is a no-op, not a second posting").isFalse();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isEqualTo(1L);
  }

  private static SaleCogsRecordedEvent event(String companyId) {
    return new SaleCogsRecordedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        companyId,
        BUSINESS_ID,
        Instant.parse("2026-08-17T00:00:00Z"),
        32_500L,
        "IDR");
  }

  private void activatePerpetualInventory(String companyId, String cutoverPeriod) throws Exception {
    activatePerpetualInventory(companyId, cutoverPeriod, true);
  }

  private void activatePerpetualInventory(
      String companyId, String cutoverPeriod, boolean perpetualActive) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO inventory_method_config"
                    + " (id, method, perpetual_active, cutover_period, activated_at, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, 'PERPETUAL', ?, ?, now(), now(), 'test', now(), 'test', 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setBoolean(2, perpetualActive);
      ps.setString(3, cutoverPeriod);
      ps.setString(4, companyId);
      ps.executeUpdate();
    }
  }

  private long journalEntryCountAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM journal_entry WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private boolean processedEventExistsAsAdmin(UUID eventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM processed_event WHERE event_id = ?")) {
      ps.setObject(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1) > 0L;
      }
    }
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps = admin.prepareStatement("SELECT count(*) FROM " + table)) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Map<String, Long> accountAmountsAsAdmin(UUID sourceEventId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT jl.account_code, jl."
                    + amountColumn
                    + " FROM journal_line jl"
                    + " JOIN journal_entry je ON je.id = jl.entry_id"
                    + " WHERE je.source_event_id = ? AND jl."
                    + amountColumn
                    + " > 0")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          byAccount.put(rs.getString(1), rs.getLong(2));
        }
      }
    }
    return byAccount;
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
