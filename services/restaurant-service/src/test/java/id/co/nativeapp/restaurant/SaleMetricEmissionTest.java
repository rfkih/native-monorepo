package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A recorded sale emits a {@code MetricPublished} ({@code sales_amount} @ employee grain) in the
 * same transaction as the {@code SaleRecorded}, attributed to the cashier who rang it (the bound
 * actor = the JWT sub) — the own-sales commission feed. When the actor is NOT a UUID (the
 * header-trust dev recipe's fixed actor), NO metric row is written and the sale is unaffected.
 */
@SpringBootTest
class SaleMetricEmissionTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private SaleService saleService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aSaleRungByAUuidActorEmitsAnEmployeeGrainSalesAmountMetric() throws Exception {
    String cashierSub = UUID.randomUUID().toString(); // a real JWT sub is a UUID
    Instant occurredAt = Instant.parse("2026-07-14T08:30:00Z");
    RecordSaleCommand command =
        new RecordSaleCommand(OUTLET, 1_000_000L, "IDR", occurredAt, "metric-key-1");

    TenantContext.callAs(TENANT_A, cashierSub, () -> saleService.recordSale(command));

    List<Map<String, Object>> metricRows =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'MetricPublished'"
                + " AND company_id = ? ORDER BY id",
            UUID.fromString(TENANT_A));
    assertThat(metricRows).hasSize(1);
    GenericRecord metric =
        AvroSerde.deserialize(
            (byte[]) metricRows.getFirst().get("payload"), MetricPublishedSchema.schema());
    assertThat(metric.get("metric_key").toString()).isEqualTo("sales_amount");
    assertThat(metric.get("grain").toString()).isEqualTo("employee");
    assertThat(metric.get("subject_id").toString()).isEqualTo(cashierSub);
    assertThat(metric.get("value")).isEqualTo(1_000_000L);
    assertThat(metric.get("period").toString()).isEqualTo("2026-07-14");
    assertThat(metric.get("source_business_id").toString()).isEqualTo(OUTLET.toString());
  }

  @Test
  void aSaleRungByANonUuidActorEmitsNoMetricButStillRecordsTheSale() throws Exception {
    Instant occurredAt = Instant.parse("2026-07-15T10:00:00Z");
    RecordSaleCommand command =
        new RecordSaleCommand(OUTLET, 500_000L, "IDR", occurredAt, "metric-key-2");

    var result =
        TenantContext.callAs(TENANT_A, "owner@console.dev", () -> saleService.recordSale(command));
    assertThat(result.created()).isTrue();

    Long metricCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'MetricPublished'"
                + " AND aggregate_id = ?",
            Long.class,
            result.sale().id().toString());
    assertThat(metricCount).isZero();
  }
}
