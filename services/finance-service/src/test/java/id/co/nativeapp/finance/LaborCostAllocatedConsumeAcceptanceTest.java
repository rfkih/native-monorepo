package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.labor.LaborCostAllocatedSchema;
import id.co.nativeapp.finance.labor.PayrollPostedSchema;
import id.co.nativeapp.finance.pnl.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end CONSUME of the Phase-3 payroll events over a real Kafka broker (#23): a {@code
 * LaborCostAllocated} bucket posts one EXPENSE {@code ledger_posting} and moves the consolidated
 * P&L expense leg; a {@code PayrollPosted} posts NO ledger row (it only reconciles the run).
 * Exercises the real transport (raw Avro bytes, the byte[] deserializer, the container's offset/ack
 * handling). Awaitility awaits the async consumption (no {@code Thread.sleep}).
 */
@SpringBootTest
class LaborCostAllocatedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private PnlReader pnlReader;

  @Test
  void aLaborBucketPostsAnExpenseAndPayrollPostedPostsNothing() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();
    long amount = 20_400_000L;

    // Publish one LaborCostAllocated bucket.
    GenericRecord bucket =
        LaborEventFixtures.laborBucket(
            run,
            1,
            TENANT_A,
            period,
            OUTLET,
            "5100-SALARY",
            amount,
            "IDR",
            false,
            false,
            occurredAt);
    LaborEventFixtures.publish(
        KAFKA.getBootstrapServers(),
        LaborCostAllocatedSchema.TOPIC,
        run.toString(),
        UUID.randomUUID(),
        bucket);

    // Publish a PayrollPosted for the same run (control total == the single bucket here).
    GenericRecord posted =
        LaborEventFixtures.payrollPosted(
            run,
            1,
            TENANT_A,
            period,
            "IDR",
            20_000_000L,
            0L,
            400_000L,
            19_600_000L,
            false,
            occurredAt);
    LaborEventFixtures.publish(
        KAFKA.getBootstrapServers(),
        PayrollPostedSchema.TOPIC,
        run.toString(),
        UUID.randomUUID(),
        posted);

    // Exactly ONE ledger posting (the labor bucket) — PayrollPosted wrote none.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));

    // PayrollPosted WAS consumed: it recorded the run's control total on the run-control table (it
    // never produces a ledger posting). The terminal reconcile state depends on cross-topic arrival
    // order (the out-of-order open risk) and is proven deterministically in
    // PayrollReconciliationTest;
    // here we only assert the control total landed, proving the consumer ran without a ledger
    // write.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(controlTotalRecordedAsAdmin(run)).isEqualTo(20_400_000L));
    // Still exactly one ledger row — PayrollPosted posted nothing.
    assertThat(ledgerCountAsAdmin()).isEqualTo(1L);

    // The P&L expense leg reflects the labor cost.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(amount, "IDR"));
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

  private long controlTotalRecordedAsAdmin(UUID runId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                "SELECT control_total_minor FROM payroll_run_ledger WHERE payroll_run_id = '"
                    + runId
                    + "'")) {
      if (!rs.next()) {
        return -1L;
      }
      long value = rs.getLong("control_total_minor");
      return rs.wasNull() ? -1L : value;
    }
  }
}
