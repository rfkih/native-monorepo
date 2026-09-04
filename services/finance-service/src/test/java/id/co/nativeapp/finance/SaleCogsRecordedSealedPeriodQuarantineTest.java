package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.inventory.messaging.SaleCogsRecordedEvent;
import id.co.nativeapp.finance.inventory.service.SaleCogsRecordedService;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0067 Phase D, D2 (Phase C review W2) — {@code SaleCogsRecordedWriter}'s sealed-period
 * quarantine, mirroring {@link SealedPeriodQuarantineTest} EXACTLY (same {@code fileCurrentPeriod}
 * arrangement: a real FILED {@code tax_filing}, not a directly-inserted row) but for a
 * TEST-ACTIVATED (perpetual-inventory) tenant: an active tenant's {@code SaleCogsRecorded} dated
 * into a sealed period must NOT post {@code Dr 5100 / Cr 1100} (nor the dimensional {@code
 * ledger_posting}) — it is quarantined to the error inbox instead, exactly like the {@code
 * SaleRecorded}/{@code RegisterSessionClosed}/{@code StocktakeCompleted}/{@code StockReceived}
 * consumers. A non-activated tenant in the SAME sealed period must stay a silent claimed no-op.
 */
@SpringBootTest
class SaleCogsRecordedSealedPeriodQuarantineTest extends PostgresRlsTestBase {

  private static final String ACTIVE_TENANT = "cccccccc-cccc-cccc-cccc-ccccccccc299";
  private static final String INACTIVE_TENANT = "cccccccc-cccc-cccc-cccc-ccccccccc298";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddd299");
  private static final String ACTOR = "sale-cogs-quarantine@integration.co.id";
  private static final String QUARANTINE_SOURCE =
      "finance.inventory.sale-cogs-recorded-sealed-period-quarantine";
  // Guaranteed never filed by this test class — well outside the "current period" the seeded VAT
  // activity below lands in.
  private static final Instant UNFILED_OCCURRED = Instant.parse("2024-03-15T10:00:00Z");

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private TaxFilingService taxFilingService;
  @Autowired private SaleCogsRecordedService saleCogsRecordedService;
  @Autowired private Clock clock;

  @BeforeEach
  void truncateErrorLog() throws Exception {
    // error_log is deliberately NOT truncated by PostgresRlsTestBase#resetTables (ADR 0005) —
    // truncate it ourselves so each test starts from an empty inbox (mirrors
    // SealedPeriodQuarantineTest).
    try (Connection admin = admin();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE error_log");
    }
  }

  /** Seeds output/input VAT then FILES the period the seeding landed in (the real filing path). */
  private Instant fileCurrentPeriod(String companyId) throws Exception {
    TenantContext.callAs(
        companyId,
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
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    TenantContext.callAs(companyId, ACTOR, () -> taxFilingService.file(period));
    return now;
  }

  @Test
  void anActiveTenantsSaleCogsRecordedIntoASealedPeriodIsQuarantinedNotPosted() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2020-01");
    Instant occurredAt = fileCurrentPeriod(ACTIVE_TENANT);
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT, occurredAt);

    boolean firstDelivery = saleCogsRecordedService.handle(event);

    assertThat(firstDelivery).as("processOnce still claims + runs the quarantine branch").isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId()))
        .as("a quarantined SaleCogsRecorded must NOT produce a journal_entry")
        .isZero();
    assertThat(rowCountAsAdmin("ledger_posting"))
        .as("a quarantined SaleCogsRecorded must NOT produce a dimensional ledger_posting")
        .isZero();
    assertThat(errorLogRowCount(QUARANTINE_SOURCE)).isEqualTo(1L);
    assertThat(errorLogOccurrenceCount(QUARANTINE_SOURCE)).isEqualTo(1L);
  }

  @Test
  void redeliveringAQuarantinedSaleCogsRecordedStaysProcessedAndPostsNothing() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT, "2020-01");
    Instant occurredAt = fileCurrentPeriod(ACTIVE_TENANT);
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT, occurredAt);

    boolean first = saleCogsRecordedService.handle(event);
    boolean redelivery = saleCogsRecordedService.handle(event);

    assertThat(first).isTrue();
    assertThat(redelivery).as("redelivery must be skipped by the processed-event claim").isFalse();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    // The redelivery never re-runs postCogs (processOnce short-circuits before it), so the error
    // inbox occurrence count must stay at exactly 1, not 2.
    assertThat(errorLogOccurrenceCount(QUARANTINE_SOURCE)).isEqualTo(1L);
  }

  @Test
  void anActiveTenantsSaleCogsRecordedInAnUnsealedPeriodPostsNormally() throws Exception {
    // Control (the "replays correctly once the period is not sealed" proof): the SAME active
    // tenant, a DIFFERENT period with no tax_filing at all, posts exactly as
    // SaleCogsRecordedWriterTest already pins — the quarantine only ever fires for a genuinely
    // sealed period, never for an active tenant in general.
    activatePerpetualInventory(ACTIVE_TENANT, "2020-01");
    SaleCogsRecordedEvent event = event(ACTIVE_TENANT, UNFILED_OCCURRED);

    boolean posted = saleCogsRecordedService.handle(event);

    assertThat(posted).isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isEqualTo(1L);
    Map<String, Long> debit = accountAmountsAsAdmin(event.eventId(), "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(event.eventId(), "credit_minor");
    assertThat(debit).containsExactlyInAnyOrderEntriesOf(Map.of("5100", 32_500L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("1100", 32_500L));
    assertThat(rowCountAsAdmin("ledger_posting")).isEqualTo(1L);
    assertThat(errorLogRowCount(QUARANTINE_SOURCE)).isZero();
  }

  @Test
  void aNonActivatedTenantInASealedPeriodStaysASilentClaimedNoOp() throws Exception {
    // Dormant-invariant proof (task CONSTRAINTS): the sealed-period gate is checked AFTER the
    // perpetual-active branch, so a non-activated tenant's claimed no-op must stay byte-identical
    // — no journal entry (expected either way) AND no error-inbox quarantine noise, even though
    // this tenant's period IS genuinely sealed.
    Instant occurredAt = fileCurrentPeriod(INACTIVE_TENANT);
    SaleCogsRecordedEvent event = event(INACTIVE_TENANT, occurredAt);

    boolean posted = saleCogsRecordedService.handle(event);

    assertThat(posted).as("still claimed, even though nothing posts").isTrue();
    assertThat(journalEntryCountAsAdmin(event.eventId())).isZero();
    assertThat(errorLogRowCount(QUARANTINE_SOURCE))
        .as("an INACTIVE tenant must never quarantine — it was already a no-op")
        .isZero();
  }

  private static SaleCogsRecordedEvent event(String companyId, Instant occurredAt) {
    return new SaleCogsRecordedEvent(
        UUID.randomUUID(), UUID.randomUUID(), companyId, BUSINESS, occurredAt, 32_500L, "IDR");
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

  // ------------------------------------------------------------------ admin helpers

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

  private long errorLogRowCount(String source) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM error_log WHERE source = ?")) {
      ps.setString(1, source);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long errorLogOccurrenceCount(String source) throws Exception {
    try (Connection admin = admin();
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

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
