package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end acceptance test for the {@code SaleVoided} → finance reversal path (B1 fix — ADR 0006,
 * slice 4).
 *
 * <p>Scenario: publish a {@code SaleRecorded} to the real Kafka topic so finance posts a PRIMARY
 * revenue ledger_posting; then publish a {@code SaleVoided} to the real Kafka topic; await the
 * reversal posting; assert:
 *
 * <ol>
 *   <li>There are exactly two {@code ledger_posting} rows (the original + the reversal contra).
 *   <li>The reversal posting has {@code posting_role = 'REVERSAL'} (M1 fix).
 *   <li>The reversal posting carries a negative amount.
 *   <li>{@code consolidated_revenue.total_minor} for the period nets to zero.
 * </ol>
 *
 * <p>Uses a real Kafka broker (Testcontainers + {@link KafkaPostgresTestBase}), so the {@link
 * id.co.nativeapp.finance.reversal.messaging.SaleVoidedListener} is exercised on the real wire —
 * not bypassed by a direct service call.
 */
@SpringBootTest
class SaleVoidedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "aaaabbbb-1111-2222-3333-000000000001";
  private static final UUID BUSINESS = UUID.fromString("aaaabbbb-1111-2222-3333-000000000002");
  private static final Instant OCCURRED = Instant.parse("2026-06-20T09:00:00Z");
  private static final String PERIOD = "2026-06";
  private static final long AMOUNT_MINOR = 25_000L;

  @Test
  void saleVoidedListenerReversesRevenueEndToEnd() throws Exception {
    // 1) Publish SaleRecorded so finance has a PRIMARY posting to reverse.
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    GenericRecord saleEvent =
        SaleRecordedFixtures.record(saleId, TENANT, BUSINESS, AMOUNT_MINOR, "IDR", OCCURRED);
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), saleId.toString(), saleEventId, saleEvent);

    // Await the PRIMARY ledger_posting (confirms sale consumed before void).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));

    // Verify consolidated_revenue was accumulated.
    assertThat(consolidatedRevenueMinor(TENANT)).isEqualTo(AMOUNT_MINOR);

    // 2) Publish SaleVoided — the real listener must consume it and post the reversal.
    UUID voidId = UUID.randomUUID();
    UUID voidEventId = UUID.randomUUID();
    GenericRecord voidEvent =
        SaleVoidedFixtures.record(
            voidId,
            saleId,
            UUID.randomUUID(), // paymentId
            TENANT,
            BUSINESS,
            AMOUNT_MINOR,
            "IDR",
            OCCURRED,
            "CASH");
    SaleVoidedFixtures.publish(
        KAFKA.getBootstrapServers(), voidId.toString(), voidEventId, voidEvent);

    // Await the REVERSAL posting (total must be 2: original + reversal).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(2L));

    // Assert the reversal posting has posting_role = REVERSAL and a negative amount.
    assertReversalPostingIsCorrect(voidId);

    // Assert consolidated_revenue netted to zero.
    assertThat(consolidatedRevenueMinor(TENANT))
        .as("void must decrement consolidated_revenue back to 0")
        .isZero();
  }

  // ------------------------------------------------------------------ helpers

  private long consolidatedRevenueMinor(String tenantId) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue"
                    + " WHERE company_id = '"
                    + tenantId
                    + "' AND period = '"
                    + PERIOD
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void assertReversalPostingIsCorrect(UUID voidId) throws Exception {
    // source_event_id on the reversal posting == the voidId (processOnce key).
    String sql =
        "SELECT posting_role, amount_minor FROM ledger_posting"
            + " WHERE source_event_id = '"
            + voidId
            + "'";
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("reversal ledger_posting must exist for voidId=" + voidId).isTrue();
      assertThat(rs.getString("posting_role"))
          .as("reversal posting_role must be REVERSAL (M1 fix)")
          .isEqualTo("REVERSAL");
      assertThat(rs.getLong("amount_minor"))
          .as("reversal posting amount must be negative")
          .isNegative();
    }
  }
}
