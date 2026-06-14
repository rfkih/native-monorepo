package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.revenue.RevenueReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (a) — the M1.5 validation goal: end-to-end CONSUME.
 *
 * <p>Publish a {@code SaleRecorded} Avro message (bytes built via {@code libs/events AvroSerde}) to
 * the real Kafka topic; finance consumes it; a {@code ledger_posting} row is created for the right
 * company / period / amount, AND the {@code consolidated_revenue} read model reflects it.
 * Awaitility awaits the async consumption (no {@code Thread.sleep}).
 *
 * <p>The full context boots, so the auto-RLS aspect is active, Flyway has run, and Hibernate {@code
 * ddl-auto=validate} has verified the mapping against the schema. The listener binds the event's
 * {@code company_id} as the tenant, so the posting writes pass the RLS {@code WITH CHECK}.
 */
@SpringBootTest
class SaleRecordedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "cashier-a@example.co.id";

  @Autowired private RevenueReader revenueReader;

  @Test
  void aPublishedSaleRecordedPostsToTheLedgerAndMovesTheReadModel() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";
    long amountMinor = 1_500_000L;

    GenericRecord event =
        SaleRecordedFixtures.record(saleId, TENANT_A, BUSINESS_A, amountMinor, "IDR", occurredAt);
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), saleId.toString(), eventId, event);

    // The consumer is async; await the ledger posting (over the admin/BYPASSRLS connection,
    // since ledger_posting is FORCE RLS and an unscoped count would otherwise be 0).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));

    // The single ledger posting has the right company / period / amount / currency. Read over the
    // admin (BYPASSRLS) connection: ledger_posting is FORCE RLS, so the app role with no bound
    // tenant scope would see zero rows (fail closed).
    Map<String, Object> posting = singleLedgerPostingAsAdmin();
    assertThat(posting.get("company_id")).isEqualTo(TENANT_A);
    assertThat(posting.get("business_id")).hasToString(BUSINESS_A.toString());
    assertThat(posting.get("period")).isEqualTo(period);
    assertThat(posting.get("posting_type")).isEqualTo("REVENUE");
    assertThat(posting.get("amount_minor")).isEqualTo(amountMinor);
    assertThat(posting.get("currency").toString().strip()).isEqualTo("IDR");
    assertThat(posting.get("source_event_id")).hasToString(eventId.toString());
    // (a) mapping resolution: the REVENUE posting resolved its gl_account via the effective
    // mapping_rule to the seeded Sales Revenue account (4000) and stamped it on the dimension.
    assertThat(posting.get("gl_account_code")).isEqualTo("4000");

    // The consolidated-revenue read model reflects it: the reader (RLS-scoped to A) sees the total.
    Optional<Money> revenue =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> revenueReader.revenueForPeriod(period));
    assertThat(revenue).contains(Money.ofMinor(amountMinor, "IDR"));
  }

  /** Counts ledger rows over the admin (BYPASSRLS) connection — sees every tenant's rows. */
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

  /** Reads the single ledger posting's fields over the admin (BYPASSRLS) connection. */
  private Map<String, Object> singleLedgerPostingAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                "SELECT company_id, business_id, period, posting_type, amount_minor, currency,"
                    + " gl_account_code, source_event_id FROM ledger_posting")) {
      rs.next();
      return Map.of(
          "company_id", rs.getString("company_id"),
          "business_id", rs.getObject("business_id"),
          "period", rs.getString("period"),
          "posting_type", rs.getString("posting_type"),
          "amount_minor", rs.getLong("amount_minor"),
          "currency", rs.getString("currency"),
          "gl_account_code", rs.getString("gl_account_code"),
          "source_event_id", rs.getObject("source_event_id"));
    }
  }
}
