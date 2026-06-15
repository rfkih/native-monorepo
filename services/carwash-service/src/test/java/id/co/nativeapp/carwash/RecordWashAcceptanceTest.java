package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.RecordWashResult;
import id.co.nativeapp.carwash.wash.messaging.SaleRecordedSchema;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance tests (a) + (b) — the #20 goal, gated by the carwash entitlement.
 *
 * <p>(a) When the company IS entitled to carwash, recording a wash persists the wash and writes
 * EXACTLY ONE {@code SaleRecorded} (the right amount / business_id / currency) PLUS one {@code
 * MetricPublished} per declared carwash metric ({@code wash_count} + {@code upsell_amount} at the
 * outlet grain), atomically. A retry with the SAME {@code idempotency_key} returns the existing
 * wash and the outbox STILL holds exactly one {@code SaleRecorded} (idempotency on retry).
 *
 * <p>(b) When the company is NOT entitled, recording a wash is rejected with {@link
 * NotEntitledException} (→ 403) and NO wash row is written and NO event is emitted.
 *
 * <p>Entitlement state is seeded through the same consumer path production uses — applying an
 * {@code EntitlementGranted} projected event in the tenant scope — so the gate reads the real local
 * projection via the Redis-cached checker. The full context boots, so the auto-RLS aspect is
 * active, Flyway has run, and {@code ddl-auto=validate} has verified the mapping.
 */
@SpringBootTest
class RecordWashAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void recordingAWashWhenEntitledWritesOneSaleRecordedAndOneMetricSetAndIsIdempotentOnRetry()
      throws Exception {
    grantCarwash(TENANT_A);

    String idempotencyKey = "wash-req-1";
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    RecordWashCommand command =
        new RecordWashCommand(
            OUTLET, "bay-1", 7_500_00L, "IDR", "wax", 2_500_00L, occurredAt, idempotencyKey);

    RecordWashResult first =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> washService.recordWash(command));
    assertThat(first.created()).isTrue();
    UUID washId = first.wash().getId();

    // Exactly one SaleRecorded, with the right payload (amount = the wash total, business =
    // outlet).
    List<Map<String, Object>> saleRows = outboxRows("SaleRecorded");
    assertThat(saleRows).hasSize(1);
    assertThat(saleRows.getFirst().get("aggregate_type")).isEqualTo("sale");
    assertThat(saleRows.getFirst().get("aggregate_id")).isEqualTo(washId.toString());
    assertThat(saleRows.getFirst().get("company_id")).hasToString(TENANT_A);
    GenericRecord sale =
        AvroSerde.deserialize(
            (byte[]) saleRows.getFirst().get("payload"), SaleRecordedSchema.schema());
    assertThat(sale.get("sale_id").toString()).isEqualTo(washId.toString());
    assertThat(sale.get("business_id").toString()).isEqualTo(OUTLET.toString());
    assertThat(sale.get("amount_minor")).isEqualTo(7_500_00L);
    assertThat(sale.get("currency").toString()).isEqualTo("IDR");
    assertThat(sale.get("occurred_at")).isEqualTo(occurredAt.toEpochMilli());

    // One MetricPublished per declared carwash metric (wash_count + upsell_amount), at the outlet
    // grain, with the right values (count=1, upsell=2_500_00).
    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    assertThat(metricRows).hasSize(2);
    Map<String, Long> byKey = decodeMetricValuesByKey(metricRows);
    assertThat(byKey).containsEntry("wash_count", 1L).containsEntry("upsell_amount", 2_500_00L);

    // Retry with the SAME idempotency key: returns the existing wash, NO 2nd events.
    RecordWashResult retry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> washService.recordWash(command));
    assertThat(retry.created()).isFalse();
    assertThat(retry.wash().getId()).isEqualTo(washId);

    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    assertThat(outboxRows("MetricPublished")).hasSize(2);
    assertThat(washRowCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void recordingAWashWhenNotEntitledIsRejectedWith403AndWritesNoRowOrEvent() throws Exception {
    // No grant for this tenant -> the gate is closed.
    RecordWashCommand command =
        new RecordWashCommand(
            OUTLET,
            "bay-1",
            5_000_00L,
            "IDR",
            null,
            null,
            Instant.parse("2026-06-14T08:30:00Z"),
            "wash-denied-1");

    Assertions.assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> washService.recordWash(command)))
        .isInstanceOf(NotEntitledException.class)
        .hasMessageContaining(CARWASH);

    // No wash row, no SaleRecorded, no MetricPublished.
    assertThat(washRowCountAsAdmin()).isZero();
    assertThat(outboxRows("SaleRecorded")).isEmpty();
    assertThat(outboxRows("MetricPublished")).isEmpty();
  }

  /** Seeds the carwash entitlement via the same projected-event consumer path production uses. */
  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, CARWASH, true));
  }

  private Map<String, Long> decodeMetricValuesByKey(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            row ->
                AvroSerde.deserialize(
                    (byte[]) row.get("payload"),
                    id.co.nativeapp.carwash.metric.messaging.MetricPublishedSchema.schema()))
        .collect(
            java.util.stream.Collectors.toMap(
                r -> r.get("metric_key").toString(), r -> (Long) r.get("value")));
  }

  private long washRowCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM wash")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<Map<String, Object>> outboxRows(String eventType) {
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, aggregate_id, company_id, payload "
            + "FROM outbox WHERE event_type = ? ORDER BY occurred_at",
        eventType);
  }
}
