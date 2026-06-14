package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.revenue.RevenueReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (b) — IDEMPOTENCY: re-delivering the SAME {@code SaleRecorded} (same event id) must NOT
 * double-count.
 *
 * <p>The same event is published twice with the same {@code id} header (an at-least-once
 * redelivery). The idempotent consumer ({@code ProcessedEventStore.processOnce}, deduping by event
 * UUID inside the posting transaction) means exactly one {@code ledger_posting} ever exists and the
 * {@code consolidated_revenue} read model holds the single amount — never twice.
 *
 * <p>To prove the second delivery was genuinely processed-and-skipped (not merely in flight), a
 * DISTINCT marker event is published after the duplicate and awaited; once the marker has posted (2
 * ledger rows total: the original + the marker), the duplicate has necessarily already passed
 * through the same single-threaded partition consumer, so asserting the original amount was not
 * doubled is race-free.
 */
@SpringBootTest
class SaleRecordedIdempotencyTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "cashier-a@example.co.id";

  @Autowired private RevenueReader revenueReader;

  @Test
  void redeliveringTheSameEventDoesNotDoubleCount() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";
    long amountMinor = 1_500_000L;

    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        SaleRecordedFixtures.record(saleId, TENANT_A, BUSINESS_A, amountMinor, "IDR", occurredAt);

    // Deliver the SAME event id twice (at-least-once redelivery of one logical event).
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), saleId.toString(), eventId, event);
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), saleId.toString(), eventId, event);

    // A distinct marker event after the duplicate; once it posts, the duplicate is necessarily
    // already drained from the same partition consumer — making the no-double-count assertion
    // race-free (records on one key go to one partition, consumed in order by one thread).
    UUID markerSaleId = UUID.randomUUID();
    UUID markerEventId = UUID.randomUUID();
    GenericRecord marker =
        SaleRecordedFixtures.record(markerSaleId, TENANT_A, BUSINESS_A, 1L, "IDR", occurredAt);
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), saleId.toString(), markerEventId, marker);

    // Await exactly two ledger rows: the original (once) + the marker. NOT three.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(2L));

    // The original amount is counted exactly once (1_500_000 + the 1-minor marker), not doubled.
    Optional<Money> revenue =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> revenueReader.revenueForPeriod(period));
    assertThat(revenue).contains(Money.ofMinor(amountMinor + 1L, "IDR"));

    // Exactly one posting carries the duplicated source_event_id.
    assertThat(postingCountForEventAsAdmin(eventId)).isEqualTo(1L);
  }

  private long ledgerCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM ledger_posting")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long postingCountForEventAsAdmin(UUID eventId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM ledger_posting WHERE source_event_id = ?")) {
      ps.setObject(1, eventId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
