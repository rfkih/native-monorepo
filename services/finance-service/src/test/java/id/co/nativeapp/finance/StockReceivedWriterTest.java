package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.inventory.messaging.StockReceivedEvent;
import id.co.nativeapp.finance.inventory.service.StockReceivedService;
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
 * ADR 0067 Phase B (finance consumer side, §1–§2, §5) — {@link StockReceivedService}/{@code
 * StockReceivedWriter} end-to-end against a REAL Testcontainers Postgres. Proves the load-bearing
 * DORMANT invariant: a company with NO {@code inventory_method_config} row (every tenant in Phase
 * B) is a claimed no-op, while a TEST-ACTIVATED tenant (a row inserted directly by this test — no
 * production activation path exists yet) capitalizes {@code Dr 1100 Inventory / Cr 2050 GRNI
 * Clearing}. A re-delivered event posts exactly once (idempotency).
 */
@SpringBootTest
class StockReceivedWriterTest extends PostgresRlsTestBase {

  private static final String ACTIVE_TENANT = "dddddddd-dddd-dddd-dddd-ddddddddddd1";
  private static final String INACTIVE_TENANT = "dddddddd-dddd-dddd-dddd-ddddddddddd2";

  @Autowired private StockReceivedService service;

  @Test
  void anActivatedTenantCapitalizesTheReceiptToInventoryAgainstGrni() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    StockReceivedEvent event = event(ACTIVE_TENANT);

    boolean posted = service.handle(event);
    assertThat(posted).isTrue();

    Map<String, Long> debit = accountAmountsAsAdmin(event.eventId(), "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(event.eventId(), "credit_minor");

    assertThat(debit)
        .as("Dr 1100 Inventory for the exact landed value")
        .containsExactlyInAnyOrderEntriesOf(Map.of("1100", 130_000L));
    assertThat(credit)
        .as("Cr 2050 GRNI Clearing for the exact landed value")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2050", 130_000L));
  }

  @Test
  void aNonActivatedTenantIsAClaimedNoOp() throws Exception {
    // No inventory_method_config row for INACTIVE_TENANT at all — the Phase B DORMANT state every
    // production tenant is in today.
    StockReceivedEvent event = event(INACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).as("the event is still claimed, even though nothing posts").isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(processedEventExistsAsAdmin(event.eventId())).isTrue();
  }

  @Test
  void anExplicitlyInactiveConfigRowIsAlsoAClaimedNoOp() throws Exception {
    activatePerpetualInventory(INACTIVE_TENANT, "2026-01", false);
    StockReceivedEvent event = event(INACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aFutureCutoverIsAlsoInactiveForAnEarlierPeriod() throws Exception {
    // cutover 2027-01 is AFTER the event's 2026-08 period — not yet active for this receipt.
    activatePerpetualInventory(ACTIVE_TENANT, "2027-01");
    StockReceivedEvent event = event(ACTIVE_TENANT);

    boolean posted = service.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
  }

  @Test
  void aRedeliveredEventPostsExactlyOnce() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2026-01");
    StockReceivedEvent event = event(ACTIVE_TENANT);

    boolean first = service.handle(event);
    boolean second = service.handle(event);

    assertThat(first).isTrue();
    assertThat(second).as("a re-delivery is a no-op, not a second posting").isFalse();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isEqualTo(1L);
  }

  private static StockReceivedEvent event(String companyId) {
    return new StockReceivedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        companyId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        10L,
        130_000L,
        "IDR",
        Instant.parse("2026-08-17T00:00:00Z"));
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
                    + " created_by, updated_at, updated_by, version, company_id, currency,"
                    + " idempotency_key, opening_inventory_value_minor)"
                    + " VALUES (?, 'PERPETUAL', ?, ?, now(), now(), 'test', now(), 'test', 0, ?,"
                    + " 'IDR', ?, 0)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setBoolean(2, perpetualActive);
      ps.setString(3, cutoverPeriod);
      ps.setString(4, companyId);
      ps.setString(5, "test-activate-" + companyId);
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
