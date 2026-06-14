package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.MetricInputProjectionService;
import id.co.nativeapp.employee.payroll.MetricProjectedEvent;
import id.co.nativeapp.employee.payroll.PeriodSealProjectionService;
import id.co.nativeapp.employee.payroll.PeriodSealedProjectedEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Idempotent re-delivery of the two consumed events (rule 3): re-applying the SAME event UUID is a
 * no-op (the {@code ProcessedEventStore} dedupes inside the projection transaction). Real
 * PostgreSQL (Testcontainers) because the dedupe uses {@code ON CONFLICT}, which H2 cannot parse.
 */
@SpringBootTest
class PayrollConsumerIdempotencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  @Autowired private PeriodSealProjectionService periodSealService;
  @Autowired private MetricInputProjectionService metricService;

  @Test
  void reDeliveredPeriodSealedIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    PeriodSealedProjectedEvent event =
        new PeriodSealedProjectedEvent(
            eventId, TENANT_A, businessId, "2026-06", Instant.ofEpochMilli(1_750_000_000_000L));

    assertThat(periodSealService.apply(event)).isTrue(); // first delivery applies
    assertThat(periodSealService.apply(event)).isFalse(); // re-delivery is skipped

    // Exactly one row exists (read over the admin/BYPASSRLS connection so RLS is not the variable).
    assertThat(countAsAdmin("SELECT COUNT(*) FROM period_seal WHERE period = '2026-06'"))
        .isEqualTo(1L);
  }

  @Test
  void reDeliveredMetricPublishedIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID subject = UUID.randomUUID();
    MetricProjectedEvent event =
        new MetricProjectedEvent(
            eventId, TENANT_A, "wash_count", "2026-06", "outlet", subject, 42L);

    assertThat(metricService.apply(event)).isTrue();
    assertThat(metricService.apply(event)).isFalse();

    assertThat(
            countAsAdmin(
                "SELECT metric_value FROM metric_input WHERE subject_id = '" + subject + "'"))
        .isEqualTo(42L);
    assertThat(
            countAsAdmin("SELECT COUNT(*) FROM metric_input WHERE subject_id = '" + subject + "'"))
        .isEqualTo(1L);
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
