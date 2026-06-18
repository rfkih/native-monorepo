package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
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
 * Acceptance (a) — the M1.4 validation goal.
 *
 * <p>Recording a sale (in a {@link TenantContext} scope) persists the sale and writes EXACTLY ONE
 * {@code SaleRecorded} row to the outbox, atomically. The payload deserializes (via {@code
 * libs/events} {@link AvroSerde}) to the right {@code sale_id} / {@code amount_minor} / {@code
 * currency}. Submitting again with the SAME {@code idempotency_key} returns the existing sale and
 * the outbox STILL holds exactly one {@code SaleRecorded} (idempotency on retry).
 *
 * <p>The full context boots, so the auto-RLS aspect is active, Flyway has run, and Hibernate {@code
 * ddl-auto=validate} has verified the mapping against the schema.
 */
@SpringBootTest
class RecordSaleAcceptanceTest extends PostgresRlsTestBase {

  // A real-company-shaped tenant id (a UUID), because the outbox company_id column
  // is UUID and the JWT company_id claim is a UUID in production.
  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";

  @Autowired private SaleService saleService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void recordingASaleWritesExactlyOneSaleRecordedAndIsIdempotentOnRetry() throws Exception {
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    String idempotencyKey = "req-abc-123";
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    RecordSaleCommand command =
        new RecordSaleCommand(businessId, 1_500_000L, "IDR", occurredAt, idempotencyKey);

    // First record: creates the sale and emits exactly one SaleRecorded.
    RecordSaleResult first =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.recordSale(command));
    assertThat(first.created()).isTrue();
    UUID saleId = first.sale().id();

    assertThat(outboxRows()).hasSize(1);
    Map<String, Object> row = outboxRows().getFirst();
    assertThat(row.get("event_type")).isEqualTo("SaleRecorded");
    assertThat(row.get("aggregate_type")).isEqualTo("sale");
    assertThat(row.get("aggregate_id")).isEqualTo(saleId.toString());
    assertThat(row.get("company_id")).hasToString(TENANT_A);

    // The Avro payload deserializes to the right sale_id / amount / currency.
    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) row.get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded.get("sale_id").toString()).isEqualTo(saleId.toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT_A);
    assertThat(decoded.get("business_id").toString()).isEqualTo(businessId.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(1_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("occurred_at")).isEqualTo(occurredAt.toEpochMilli());

    // Retry with the SAME idempotency key: returns the existing sale, NO 2nd event.
    RecordSaleResult retry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.recordSale(command));
    assertThat(retry.created()).isFalse();
    assertThat(retry.sale().id()).isEqualTo(saleId);

    // Still exactly one SaleRecorded in the outbox after the retry.
    assertThat(outboxRows()).hasSize(1);

    // And the idempotent retry resolved to the same single sale row. Counted over
    // the admin (BYPASSRLS) connection because `sale` is FORCE RLS: a plain count
    // under the unprivileged app role with no tenant GUC would fail closed (0).
    assertThat(saleRowCountAsAdmin()).isEqualTo(1L);
  }

  private long saleRowCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM sale")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<Map<String, Object>> outboxRows() {
    // The outbox is not under RLS, so the app role reads every row directly.
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, aggregate_id, company_id, payload "
            + "FROM outbox WHERE event_type = 'SaleRecorded' ORDER BY occurred_at");
  }
}
