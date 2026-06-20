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
 * End-to-end acceptance test for the {@code SaleRefunded} → finance reversal path (B1 fix — ADR
 * 0006, slice 4).
 *
 * <p>Scenario: publish a {@code SaleRecorded} to the real Kafka topic so finance posts a PRIMARY
 * revenue ledger_posting; then publish a {@code SaleRefunded} to the real Kafka topic for a partial
 * refund; await the reversal posting; assert:
 *
 * <ol>
 *   <li>There are exactly two {@code ledger_posting} rows (the original + the refund contra).
 *   <li>The refund posting has {@code posting_role = 'REVERSAL'} (M1 fix).
 *   <li>The refund posting carries a negative amount equal to the refunded amount.
 *   <li>{@code consolidated_revenue.total_minor} for the period equals (original - refund).
 * </ol>
 *
 * <p>Uses a real Kafka broker (Testcontainers + {@link KafkaPostgresTestBase}), so the {@link
 * id.co.nativeapp.finance.reversal.messaging.SaleRefundedListener} is exercised on the real wire —
 * not bypassed by a direct service call.
 */
@SpringBootTest
class SaleRefundedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "aaaabbbb-1111-2222-3333-000000000003";
  private static final UUID BUSINESS = UUID.fromString("aaaabbbb-1111-2222-3333-000000000004");
  private static final Instant OCCURRED = Instant.parse("2026-06-20T10:00:00Z");
  private static final String PERIOD = "2026-06";
  private static final long SALE_AMOUNT = 60_000L;
  private static final long REFUND_AMOUNT = 20_000L;

  @Test
  void saleRefundedListenerPostsReversalEndToEnd() throws Exception {
    // 1) Publish SaleRecorded so finance has a PRIMARY posting to partially reverse.
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    GenericRecord saleEvent =
        SaleRecordedFixtures.record(saleId, TENANT, BUSINESS, SALE_AMOUNT, "IDR", OCCURRED);
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), saleId.toString(), saleEventId, saleEvent);

    // Await the PRIMARY posting.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(1L));

    assertThat(consolidatedRevenueMinor(TENANT)).isEqualTo(SALE_AMOUNT);

    // 2) Publish SaleRefunded (partial refund) — the real listener must consume and post.
    UUID refundId = UUID.randomUUID();
    UUID refundEventId = UUID.randomUUID();
    GenericRecord refundEvent =
        SaleRefundedFixtures.record(
            refundId,
            saleId,
            UUID.randomUUID(), // paymentId
            TENANT,
            BUSINESS,
            REFUND_AMOUNT,
            REFUND_AMOUNT, // totalRefundedMinor == refundAmount (first partial)
            "IDR",
            OCCURRED,
            "QRIS");
    SaleRefundedFixtures.publish(
        KAFKA.getBootstrapServers(), refundId.toString(), refundEventId, refundEvent);

    // Await the REVERSAL posting (total must be 2).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(2L));

    // Assert the reversal posting has posting_role = REVERSAL and a negative amount.
    assertRefundPostingIsCorrect(refundId);

    // Assert consolidated_revenue is (sale - refund).
    assertThat(consolidatedRevenueMinor(TENANT))
        .as("partial refund must reduce consolidated_revenue by the refunded amount")
        .isEqualTo(SALE_AMOUNT - REFUND_AMOUNT);
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

  private void assertRefundPostingIsCorrect(UUID refundId) throws Exception {
    String sql =
        "SELECT posting_role, amount_minor FROM ledger_posting"
            + " WHERE source_event_id = '"
            + refundId
            + "'";
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next())
          .as("refund ledger_posting must exist for refundId=" + refundId)
          .isTrue();
      assertThat(rs.getString("posting_role"))
          .as("refund posting_role must be REVERSAL (M1 fix)")
          .isEqualTo("REVERSAL");
      assertThat(rs.getLong("amount_minor"))
          .as("refund posting amount must be negative")
          .isNegative();
    }
  }
}
