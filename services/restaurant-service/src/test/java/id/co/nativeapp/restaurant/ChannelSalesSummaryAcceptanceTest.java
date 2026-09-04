package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.sale.dto.ChannelSalesSummaryResponse;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers acceptance test for {@code GET /api/v1/sales/channel-summary} — the per-channel
 * ONLINE sales summary for a period ({@code SaleRepository.findChannelSummary}). Mirrors {@code
 * SaleHistoryAcceptanceTest}'s style: real sales recorded via {@link SaleService#recordSale} with
 * an explicit {@code occurredAt}, then read back through the service.
 */
@SpringBootTest
class ChannelSalesSummaryAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-aaaaaaaaaaaa";
  private static final String TENANT_B = "11111111-1111-1111-1111-bbbbbbbbbbbb";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String PERIOD = "2026-06";
  private static final String OTHER_PERIOD = "2026-07";

  @Autowired private SaleService saleService;

  @Test
  void twoChannelsAggregateCorrectlyExcludingNonOnlineAndOtherPeriods() throws Exception {
    // GOFOOD: two ONLINE sales in the period -> summed, count 2.
    recordOnline(TENANT_A, ACTOR_A, "gofood-1", 50_000L, "GOFOOD", "2026-06-10T10:00:00Z");
    recordOnline(TENANT_A, ACTOR_A, "gofood-2", 70_000L, "GOFOOD", "2026-06-20T09:00:00Z");
    // GRABFOOD: one ONLINE sale in the period.
    recordOnline(TENANT_A, ACTOR_A, "grab-1", 30_000L, "GRABFOOD", "2026-06-15T12:00:00Z");
    // A non-ONLINE (CASH) sale in the SAME period -> channel_code is null, must be excluded.
    recordCash(TENANT_A, ACTOR_A, "cash-1", 999_000L, "2026-06-12T08:00:00Z");
    // An ONLINE sale in a DIFFERENT period -> must not leak into this period's totals.
    recordOnline(
        TENANT_A, ACTOR_A, "gofood-other-period", 999_000L, "GOFOOD", "2026-07-05T09:00:00Z");

    List<ChannelSalesSummaryResponse> summary =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.channelSalesSummary(PERIOD));

    assertThat(summary).hasSize(2);
    ChannelSalesSummaryResponse gofood =
        summary.stream().filter(r -> r.channelCode().equals("GOFOOD")).findFirst().orElseThrow();
    assertThat(gofood.grossSalesMinor()).isEqualTo(120_000L);
    assertThat(gofood.transactionCount()).isEqualTo(2L);
    assertThat(gofood.currency()).isEqualTo("IDR");

    ChannelSalesSummaryResponse grabfood =
        summary.stream().filter(r -> r.channelCode().equals("GRABFOOD")).findFirst().orElseThrow();
    assertThat(grabfood.grossSalesMinor()).isEqualTo(30_000L);
    assertThat(grabfood.transactionCount()).isEqualTo(1L);
    assertThat(grabfood.currency()).isEqualTo("IDR");
  }

  @Test
  void aPeriodWithNoOnlineSalesReturnsAnEmptyList() throws Exception {
    recordOnline(TENANT_A, ACTOR_A, "gofood-june", 50_000L, "GOFOOD", "2026-06-10T10:00:00Z");

    List<ChannelSalesSummaryResponse> summary =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> saleService.channelSalesSummary(OTHER_PERIOD));

    assertThat(summary).isEmpty();
  }

  @Test
  void aDifferentTenantNeverSeesAnotherTenantsChannelSummary() throws Exception {
    recordOnline(TENANT_A, ACTOR_A, "gofood-a", 100_000L, "GOFOOD", "2026-06-10T10:00:00Z");
    recordOnline(TENANT_B, ACTOR_B, "gofood-b", 40_000L, "GOFOOD", "2026-06-11T10:00:00Z");

    List<ChannelSalesSummaryResponse> summaryA =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.channelSalesSummary(PERIOD));
    List<ChannelSalesSummaryResponse> summaryB =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> saleService.channelSalesSummary(PERIOD));

    assertThat(summaryA)
        .singleElement()
        .satisfies(r -> assertThat(r.grossSalesMinor()).isEqualTo(100_000L));
    assertThat(summaryB)
        .singleElement()
        .satisfies(r -> assertThat(r.grossSalesMinor()).isEqualTo(40_000L));
  }

  private void recordOnline(
      String tenant, String actor, String key, long amountMinor, String channel, String occurredAt)
      throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            BUSINESS, amountMinor, "IDR", Instant.parse(occurredAt), key, "ONLINE", null, channel);
    TenantContext.callAs(tenant, actor, () -> saleService.recordSale(command));
  }

  private void recordCash(
      String tenant, String actor, String key, long amountMinor, String occurredAt)
      throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(BUSINESS, amountMinor, "IDR", Instant.parse(occurredAt), key, "CASH");
    TenantContext.callAs(tenant, actor, () -> saleService.recordSale(command));
  }
}
