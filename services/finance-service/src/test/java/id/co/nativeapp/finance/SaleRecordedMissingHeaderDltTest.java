package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * FAIL-CLOSED proof for a missing event-id header (HR-3 / §4).
 *
 * <p>A VALID {@code SaleRecorded} Avro value is published WITHOUT the {@code id} Kafka header — a
 * misconfigured producer (the Debezium outbox router always stamps {@code id}). The consumer must
 * NOT synthesise a non-durable positional id (which would defeat dedupe after a rebalance /
 * compacted replay / repartition and risk double-counting money). Instead it fails closed with a
 * non-retryable {@code MissingEventIdException}, so the record is routed to {@code
 * SaleRecorded.DLT} and NO {@code ledger_posting} is written.
 *
 * <p>A valid event WITH an {@code id} header follows on the same key/partition to make the
 * no-posting assertion race-free: once the marker posts its single ledger row, the header-less
 * record ahead of it has necessarily already been handled (and DLT'd).
 */
@SpringBootTest
class SaleRecordedMissingHeaderDltTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String DLT_TOPIC = SaleRecordedSchema.TOPIC + ".DLT";

  @Test
  void aRecordWithNoEventIdHeaderLandsOnTheDltAndWritesNoLedgerPosting() throws Exception {
    String key = UUID.randomUUID().toString();
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    // A perfectly valid Avro value, but published WITHOUT the id header (a contract violation).
    GenericRecord headerless =
        SaleRecordedFixtures.record(
            UUID.randomUUID(), TENANT_A, BUSINESS_A, 1_500_000L, "IDR", occurredAt);
    SaleRecordedFixtures.publishWithoutEventIdHeader(KAFKA.getBootstrapServers(), key, headerless);

    // A valid, well-formed marker right after it on the same partition.
    UUID markerEventId = UUID.randomUUID();
    GenericRecord marker =
        SaleRecordedFixtures.record(UUID.randomUUID(), TENANT_A, BUSINESS_A, 1L, "IDR", occurredAt);
    SaleRecordedFixtures.publish(KAFKA.getBootstrapServers(), key, markerEventId, marker);

    // The header-less record is on SaleRecorded.DLT.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        DltProbe.awaitKeyOnTopic(
                            KAFKA.getBootstrapServers(), DLT_TOPIC, key, Duration.ofSeconds(5)))
                    .isTrue());

    // Exactly one ledger posting — the marker's. The header-less record wrote none (fail closed).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));
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
}
