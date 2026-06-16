package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * POISON / DLT fail-safe proof (HR-3 / §4) — money is never silently dropped.
 *
 * <p>GARBAGE bytes (not a valid {@code SaleRecorded} Avro payload) are published to the topic with
 * a valid {@code id} header, so the failure is specifically the Avro DECODE. The listener surfaces
 * a non-retryable {@code SaleRecordedDecodeException}, and the container's error handler routes the
 * record to {@code SaleRecorded.DLT} (no retry budget is spent, since it is non-retryable) — never
 * an infinite in-place retry that would block the partition. We CONSUME from {@code
 * SaleRecorded.DLT} to prove the record landed there, and assert NO {@code ledger_posting} was
 * written (the poison record produced no side effect).
 *
 * <p>To make the no-posting assertion race-free, a VALID {@code SaleRecorded} is published right
 * after the poison record on the same key/partition: once that valid event has posted its single
 * ledger row, the poison record (ahead of it on the partition) has necessarily already been handled
 * (and DLT'd), so asserting exactly one ledger posting exists is deterministic.
 */
@SpringBootTest
class SaleRecordedDltTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String DLT_TOPIC = SaleRecordedSchema.TOPIC + ".DLT";

  @Test
  void garbageBytesLandOnTheDltAndWriteNoLedgerPosting() throws Exception {
    String key = UUID.randomUUID().toString();
    UUID poisonEventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    // Publish garbage that is not a valid SaleRecorded Avro record (valid id header, so the
    // failure is the DECODE, not a missing header).
    byte[] garbage = "this-is-not-avro".getBytes(StandardCharsets.UTF_8);
    SaleRecordedFixtures.publishBytes(KAFKA.getBootstrapServers(), key, poisonEventId, garbage);

    // A valid event right after it on the same key/partition, so once IT posts the poison record
    // is necessarily already drained and DLT'd (single-threaded partition consumer, in order).
    UUID markerSaleId = UUID.randomUUID();
    UUID markerEventId = UUID.randomUUID();
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(),
        key,
        markerEventId,
        SaleRecordedFixtures.record(markerSaleId, TENANT_A, BUSINESS_A, 1L, "IDR", occurredAt));

    // The garbage record is on SaleRecorded.DLT.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        DltProbe.awaitKeyOnTopic(
                            KAFKA.getBootstrapServers(), DLT_TOPIC, key, Duration.ofSeconds(5)))
                    .isTrue());

    // Exactly one ledger posting — the marker's. The poison record wrote none.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));
  }
}
