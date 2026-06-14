package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.labor.LaborCostAllocatedSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * POISON / DLT fail-safe proof for {@code LaborCostAllocated} (#23 / HR-3) — money is never
 * silently dropped. Garbage bytes (a valid {@code id} header, so the failure is specifically the
 * Avro DECODE) surface a non-retryable {@code LaborCostAllocatedDecodeException}; the container
 * routes the record to {@code LaborCostAllocated.DLT} (no retry budget spent) and writes NO ledger
 * posting. A VALID bucket published right after on the same key makes the no-posting assertion
 * race-free: once it posts its single row, the poison record ahead of it on the partition has
 * necessarily been DLT'd.
 */
@SpringBootTest
class LaborCostAllocatedDltTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String DLT_TOPIC = LaborCostAllocatedSchema.TOPIC + ".DLT";

  @Test
  void garbageBytesLandOnTheDltAndWriteNoLedgerPosting() throws Exception {
    String key = UUID.randomUUID().toString();
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");

    // Garbage that is not a valid LaborCostAllocated Avro record (valid id header -> DECODE
    // failure).
    byte[] garbage = "this-is-not-avro".getBytes(StandardCharsets.UTF_8);
    LaborEventFixtures.publishBytes(
        KAFKA.getBootstrapServers(),
        LaborCostAllocatedSchema.TOPIC,
        key,
        UUID.randomUUID(),
        garbage);

    // A valid bucket right after it on the same key/partition; once IT posts, the poison record is
    // necessarily already drained and DLT'd (in-order single-partition consumer).
    UUID run = UUID.randomUUID();
    LaborEventFixtures.publish(
        KAFKA.getBootstrapServers(),
        LaborCostAllocatedSchema.TOPIC,
        key,
        UUID.randomUUID(),
        LaborEventFixtures.laborBucket(
            run,
            1,
            TENANT_A,
            "2026-06",
            OUTLET,
            "5100-SALARY",
            1L,
            "IDR",
            false,
            false,
            occurredAt));

    // The garbage record is on LaborCostAllocated.DLT.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        DltProbe.awaitKeyOnTopic(
                            KAFKA.getBootstrapServers(), DLT_TOPIC, key, Duration.ofSeconds(5)))
                    .isTrue());

    // Exactly one ledger posting — the valid bucket's. The poison record wrote none.
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
