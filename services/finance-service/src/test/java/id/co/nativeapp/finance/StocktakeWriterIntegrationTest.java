package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
import id.co.nativeapp.finance.stocktake.service.StocktakeService;
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
 * ADR 0068 part 1 (finance consumer side) — {@link StocktakeService}/{@code StocktakeWriter}
 * end-to-end against a REAL Testcontainers Postgres. Proves the load-bearing DORMANT invariant a
 * count typo can no longer reach: a company with NO {@code inventory_method_config} row (every
 * tenant by default) is a claimed no-op — NO {@code journal_entry} and NO {@code ledger_posting},
 * closing the phantom-profit class ADR 0068 describes — while a TEST-ACTIVATED
 * (perpetual-inventory) tenant still gets the ADR 0038 true-up UNCHANGED (both the LOSS and GAIN
 * directions, plus the dimensional {@code ledger_posting} row). A re-delivered event posts at most
 * once in either mode (mirrors {@code StockReceivedWriterTest} exactly).
 */
@SpringBootTest
class StocktakeWriterIntegrationTest extends PostgresRlsTestBase {

  private static final String ACTIVE_TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee01";
  private static final String INACTIVE_TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee02";
  private static final UUID BUSINESS_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeee99");

  @Autowired private StocktakeService stocktakeService;

  @Test
  void aNonActivatedTenantIsAClaimedNoOpWritingNoJournalAndNoLedgerPosting() throws Exception {
    // No inventory_method_config row for INACTIVE_TENANT at all — the DEFAULT state every tenant
    // is in today (the phantom-profit class ADR 0068 closes).
    StocktakeCompletedEvent event = event(INACTIVE_TENANT, 75_000L);

    boolean posted = stocktakeService.handle(event);

    assertThat(posted).as("the event is still claimed, even though nothing posts").isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isZero();
    assertThat(processedEventExistsAsAdmin(event.eventId())).isTrue();
  }

  @Test
  void aNonActivatedTenantsGainIsAlsoAClaimedNoOp() throws Exception {
    StocktakeCompletedEvent event = event(INACTIVE_TENANT, -40_000L);

    boolean posted = stocktakeService.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aRedeliveredEventForANonActivatedTenantPostsNothingEitherTime() throws Exception {
    StocktakeCompletedEvent event = event(INACTIVE_TENANT, 75_000L);

    boolean first = stocktakeService.handle(event);
    boolean second = stocktakeService.handle(event);

    assertThat(first).isTrue();
    assertThat(second).as("a re-delivery is a no-op, not a second (no-)posting").isFalse();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void anActivatedTenantsLossPostsTheTrueUpAndTheDimensionalLedgerPosting() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    StocktakeCompletedEvent event = event(ACTIVE_TENANT, 75_000L);

    boolean posted = stocktakeService.handle(event);
    assertThat(posted).isTrue();

    Map<String, Long> debit = accountAmountsAsAdmin(event.eventId(), "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(event.eventId(), "credit_minor");
    assertThat(debit)
        .as("Dr 5800 Inventory Shrinkage for the shrinkage magnitude")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5800", 75_000L));
    assertThat(credit)
        .as("Cr 1100 Inventory for the shrinkage magnitude")
        .containsExactlyInAnyOrderEntriesOf(Map.of("1100", 75_000L));

    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isEqualTo(1L);
    assertThat(ledgerPostingAccountAsAdmin(event.eventId())).isEqualTo("5800");
    assertThat(ledgerPostingAmountMinorAsAdmin(event.eventId())).isEqualTo(75_000L);
  }

  @Test
  void anActivatedTenantsGainPostsTheTrueUpAndTheDimensionalLedgerPosting() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    StocktakeCompletedEvent event = event(ACTIVE_TENANT, -40_000L);

    boolean posted = stocktakeService.handle(event);
    assertThat(posted).isTrue();

    Map<String, Long> debit = accountAmountsAsAdmin(event.eventId(), "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(event.eventId(), "credit_minor");
    assertThat(debit)
        .as("Dr 1100 Inventory for the gain magnitude")
        .containsExactlyInAnyOrderEntriesOf(Map.of("1100", 40_000L));
    assertThat(credit)
        .as("Cr 5800 Inventory Shrinkage for the gain magnitude")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5800", 40_000L));

    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isEqualTo(1L);
    assertThat(ledgerPostingAccountAsAdmin(event.eventId())).isEqualTo("5800");
    // The read-model figure carries shrinkage_minor's sign verbatim (negative = contra-expense).
    assertThat(ledgerPostingAmountMinorAsAdmin(event.eventId())).isEqualTo(-40_000L);
  }

  @Test
  void aFutureCutoverIsAlsoInactiveForAnEarlierPeriod() throws Exception {
    // cutover 2027-01 is AFTER the event's 2026-08 period — not yet active for this stocktake.
    activatePerpetualInventory(ACTIVE_TENANT, "2027-01");
    StocktakeCompletedEvent event = event(ACTIVE_TENANT, 75_000L);

    boolean posted = stocktakeService.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aRedeliveredEventForAnActivatedTenantPostsExactlyOnce() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    StocktakeCompletedEvent event = event(ACTIVE_TENANT, 75_000L);

    boolean first = stocktakeService.handle(event);
    boolean second = stocktakeService.handle(event);

    assertThat(first).isTrue();
    assertThat(second).as("a re-delivery is a no-op, not a second posting").isFalse();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isEqualTo(1L);
    assertThat(ledgerPostingCountAsAdmin(event.eventId())).isEqualTo(1L);
  }

  private static StocktakeCompletedEvent event(String companyId, long shrinkageMinor) {
    return new StocktakeCompletedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        companyId,
        BUSINESS_ID,
        Instant.parse("2026-08-17T00:00:00Z"),
        shrinkageMinor,
        "IDR");
  }

  private void activatePerpetualInventory(String companyId, String cutoverPeriod) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO inventory_method_config"
                    + " (id, method, perpetual_active, cutover_period, activated_at, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id, currency,"
                    + " idempotency_key, opening_inventory_value_minor)"
                    + " VALUES (?, 'PERPETUAL', true, ?, now(), now(), 'test', now(), 'test', 0,"
                    + " ?, 'IDR', ?, 0)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, cutoverPeriod);
      ps.setString(3, companyId);
      ps.setString(4, "test-activate-" + companyId);
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

  private long ledgerPostingCountAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM ledger_posting WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String ledgerPostingAccountAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT gl_account_code FROM ledger_posting WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private long ledgerPostingAmountMinorAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT amount_minor FROM ledger_posting WHERE source_event_id = ?")) {
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
