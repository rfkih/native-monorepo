package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.platform.dto.PlatformSettlementSummaryResponse;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers acceptance test for {@code GET /api/v1/platform-settlements/summary} — the
 * per-channel settlement summary for a period ({@code PlatformSettlementRepository.findSummary}).
 *
 * <p>Rows are inserted directly over the admin/BYPASSRLS connection (the {@code
 * SaleHistoryAcceptanceTest} idiom) rather than through {@link PlatformSettlementWriter#settle},
 * because {@code settle} always stamps {@code settled_at} from the injected {@link java.time.Clock}
 * ("now") — there is no public way to backdate a settlement into an arbitrary past period, and this
 * is a pure read-aggregate test over the existing table (no GL/money-path writes).
 */
@SpringBootTest
class PlatformSettlementSummaryAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-dddddddddddd";
  private static final String TENANT_B = "11111111-1111-1111-1111-eeeeeeeeeeef";
  private static final String ACTOR = "summary@integration.co.id";
  private static final String PERIOD = "2026-06";
  private static final String OTHER_PERIOD = "2026-07";

  @Autowired private PlatformSettlementWriter writer;

  @Test
  void twoChannelsAggregateCorrectlyForThePeriod() throws Exception {
    // GOFOOD: two settlements in the period -> summed, count 2.
    insertSettlement(TENANT_A, "GOFOOD", 100_000L, 90_000L, "2026-06-05T10:00:00Z");
    insertSettlement(TENANT_A, "GOFOOD", 200_000L, 180_000L, "2026-06-20T10:00:00Z");
    // GRABFOOD: one settlement in the period.
    insertSettlement(TENANT_A, "GRABFOOD", 50_000L, 47_000L, "2026-06-15T10:00:00Z");
    // A settlement in a DIFFERENT period -> must not leak into this period's totals.
    insertSettlement(TENANT_A, "GOFOOD", 999_000L, 900_000L, "2026-07-01T10:00:00Z");

    List<PlatformSettlementSummaryResponse> summary =
        TenantContext.callAs(TENANT_A, ACTOR, () -> writer.summary(PERIOD));

    assertThat(summary).hasSize(2);
    PlatformSettlementSummaryResponse gofood =
        summary.stream().filter(r -> r.channelCode().equals("GOFOOD")).findFirst().orElseThrow();
    assertThat(gofood.settledGrossMinor()).isEqualTo(300_000L);
    assertThat(gofood.netMinor()).isEqualTo(270_000L);
    assertThat(gofood.feeMinor()).isEqualTo(30_000L);
    assertThat(gofood.settlementCount()).isEqualTo(2L);
    assertThat(gofood.currency()).isEqualTo("IDR");

    PlatformSettlementSummaryResponse grabfood =
        summary.stream().filter(r -> r.channelCode().equals("GRABFOOD")).findFirst().orElseThrow();
    assertThat(grabfood.settledGrossMinor()).isEqualTo(50_000L);
    assertThat(grabfood.netMinor()).isEqualTo(47_000L);
    assertThat(grabfood.feeMinor()).isEqualTo(3_000L);
    assertThat(grabfood.settlementCount()).isEqualTo(1L);
  }

  @Test
  void aPeriodWithNoSettlementsReturnsAnEmptyList() throws Exception {
    insertSettlement(TENANT_A, "GOFOOD", 100_000L, 90_000L, "2026-06-05T10:00:00Z");

    List<PlatformSettlementSummaryResponse> summary =
        TenantContext.callAs(TENANT_A, ACTOR, () -> writer.summary(OTHER_PERIOD));

    assertThat(summary).isEmpty();
  }

  @Test
  void aDifferentTenantNeverSeesAnotherTenantsSettlementSummary() throws Exception {
    insertSettlement(TENANT_A, "GOFOOD", 100_000L, 90_000L, "2026-06-05T10:00:00Z");
    insertSettlement(TENANT_B, "GOFOOD", 40_000L, 36_000L, "2026-06-06T10:00:00Z");

    List<PlatformSettlementSummaryResponse> summaryA =
        TenantContext.callAs(TENANT_A, ACTOR, () -> writer.summary(PERIOD));
    List<PlatformSettlementSummaryResponse> summaryB =
        TenantContext.callAs(TENANT_B, ACTOR, () -> writer.summary(PERIOD));

    assertThat(summaryA)
        .singleElement()
        .satisfies(r -> assertThat(r.settledGrossMinor()).isEqualTo(100_000L));
    assertThat(summaryB)
        .singleElement()
        .satisfies(r -> assertThat(r.settledGrossMinor()).isEqualTo(40_000L));
  }

  /**
   * Inserts a {@code platform_settlement} row directly (admin/BYPASSRLS connection) — see the class
   * javadoc for why this bypasses {@link PlatformSettlementWriter#settle}.
   */
  private void insertSettlement(
      String companyId, String channelCode, long grossMinor, long netMinor, String settledAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    long feeMinor = grossMinor - netMinor;
    Timestamp ts = Timestamp.from(Instant.parse(settledAt));
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO platform_settlement (id, channel_code, gross_minor, net_minor,"
                    + " fee_minor, currency, journal_entry_id, settled_at, idempotency_key,"
                    + " created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, ?, ?, 'IDR', ?, ?, ?, ?, 'test', ?, 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, channelCode);
      ps.setLong(3, grossMinor);
      ps.setLong(4, netMinor);
      ps.setLong(5, feeMinor);
      ps.setObject(6, UUID.randomUUID());
      ps.setTimestamp(7, ts);
      ps.setString(8, "summary-" + id);
      ps.setTimestamp(9, ts);
      ps.setTimestamp(10, ts);
      ps.setString(11, companyId);
      ps.executeUpdate();
    }
  }
}
