package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the ADR 0028 security-review defense-in-depth guard in {@link
 * id.co.nativeapp.finance.revenue.service.RevenuePostingWriter#post} — a {@code SaleRecorded}
 * whose period already has a FILED {@code tax_filing} (the VAT return seals it, ADR 0017) is
 * QUARANTINED: nothing is posted to the ledger/read-models, the event is recorded to the error
 * inbox instead (source {@code finance.revenue.sealed-period-quarantine}), and the delivery is
 * still marked {@code processed} so a redelivery can never retry it into the sealed books.
 *
 * <p>Arranges a real FILED {@code tax_filing} the same way {@link TaxFilingConcurrencyTest} does —
 * a draft+issued AR invoice and a draft+posted AP bill under {@link TenantContext#callAs}, then
 * {@link TaxFilingService#file(String)} for the period the seeding lands in — rather than inserting
 * {@code tax_filing} directly, so the arrangement exercises the real filing path that {@link
 * id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository#sealedPeriodExists} probes.
 */
@SpringBootTest
class SealedPeriodQuarantineTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-ccccccccc099";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddd099");
  private static final String ACTOR = "tax-quarantine@integration.co.id";
  private static final String QUARANTINE_SOURCE = "finance.revenue.sealed-period-quarantine";
  // Guaranteed never filed by this test class — well outside the "current period" the seeded VAT
  // activity below lands in, and untouched by any tax_filing arrangement here.
  private static final Instant UNFILED_OCCURRED = Instant.parse("2024-03-15T10:00:00Z");

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private TaxFilingService taxFilingService;
  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private Clock clock;

  @BeforeEach
  void truncateErrorLog() throws Exception {
    // error_log is deliberately NOT truncated by PostgresRlsTestBase#resetTables (ADR 0005) —
    // truncate it ourselves so each test starts from an empty inbox (mirrors ErrorInboxWriterTest).
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE error_log");
    }
  }

  /**
   * Seeds output VAT 1,100,000 + input VAT 440,000 (net 660,000 PAYABLE) — same arrangement as
   * {@link TaxFilingConcurrencyTest#seedVat} — then FILES the period the seeding landed in. Returns
   * the instant the filing was made at, so callers can build a {@link SaleRecordedEvent} whose
   * {@code occurredAt} falls inside the now-sealed period.
   */
  private Instant fileCurrentPeriod() throws Exception {
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
                  List.of(new BillLineInput("Purchase", 1, 4_000_000L)));
          billWriter.post(bill, 30);
          return null;
        });
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    TenantContext.callAs(TENANT, ACTOR, () -> taxFilingService.file(period));
    return now;
  }

  @Test
  void sealedPeriodQuarantinesTheSaleWithoutPostingAndRecordsToTheErrorInbox() throws Exception {
    Instant occurredAt = fileCurrentPeriod();
    String period = LedgerPosting.periodOf(occurredAt);
    UUID eventId = UUID.randomUUID();
    SaleRecordedEvent event =
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(500_000L, "IDR"), occurredAt, "CASH");

    boolean firstDelivery = revenuePostingService.handle(event);

    // processOnce claims + runs the handler on the first delivery even though the quarantine
    // branch posts nothing to the ledger — exactly the "still marked processed" contract.
    assertThat(firstDelivery).isTrue();

    assertThat(ledgerPostingCountForSourceEvent(eventId))
        .as("a quarantined SaleRecorded must NOT produce a ledger_posting row")
        .isZero();
    assertThat(consolidatedRevenueMinorAsAdmin(TENANT, period))
        .as("a quarantined SaleRecorded must NOT accumulate consolidated_revenue for the period")
        .isZero();

    assertThat(errorLogRowCount(QUARANTINE_SOURCE)).isEqualTo(1L);
    assertThat(errorLogOccurrenceCount(QUARANTINE_SOURCE)).isEqualTo(1L);
  }

  @Test
  void redeliveringAQuarantinedEventStaysProcessedAndPostsNothing() throws Exception {
    Instant occurredAt = fileCurrentPeriod();
    String period = LedgerPosting.periodOf(occurredAt);
    UUID eventId = UUID.randomUUID();
    SaleRecordedEvent event =
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(500_000L, "IDR"), occurredAt, "CASH");

    boolean first = revenuePostingService.handle(event);
    boolean redelivery = revenuePostingService.handle(event);

    assertThat(first).isTrue();
    assertThat(redelivery).as("redelivery must be skipped by the processed-event claim").isFalse();

    assertThat(ledgerPostingCountForSourceEvent(eventId)).isZero();
    assertThat(consolidatedRevenueMinorAsAdmin(TENANT, period)).isZero();
    // The redelivery never re-runs postRevenue (processOnce short-circuits before it), so the
    // error inbox occurrence count must stay at exactly 1, not 2.
    assertThat(errorLogOccurrenceCount(QUARANTINE_SOURCE)).isEqualTo(1L);
  }

  @Test
  void unfiledPeriodPostsNormally() throws Exception {
    // Control: an event in a period that has NO tax_filing must post exactly as before ADR 0028's
    // quarantine — the quarantine never fires for an honest, unsealed period.
    UUID eventId = UUID.randomUUID();
    SaleRecordedEvent event =
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(250_000L, "IDR"), UNFILED_OCCURRED, "CASH");

    boolean posted = revenuePostingService.handle(event);

    assertThat(posted).isTrue();
    assertThat(ledgerPostingCountForSourceEvent(eventId)).isEqualTo(1L);
    assertThat(consolidatedRevenueMinorAsAdmin(TENANT, LedgerPosting.periodOf(UNFILED_OCCURRED)))
        .isEqualTo(250_000L);
    assertThat(errorLogRowCount(QUARANTINE_SOURCE)).isZero();
  }

  // ------------------------------------------------------------------ admin helpers

  private long ledgerPostingCountForSourceEvent(UUID sourceEventId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  private long consolidatedRevenueMinorAsAdmin(String tenantId, String period) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue"
                    + " WHERE company_id = ? AND period = ?")) {
      ps.setString(1, tenantId);
      ps.setString(2, period);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long errorLogRowCount(String source) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
