package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.service.OutletRevenueReader;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.revenue.service.RevenueReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the {@code outlet_revenue} write path in {@link
 * id.co.nativeapp.finance.revenue.service.RevenuePostingWriter}.
 *
 * <p>Acceptance criteria:
 *
 * <ol>
 *   <li>Two {@code SaleRecorded} events from different {@code business_id}s in the same
 *       tenant/period produce two distinct {@code outlet_revenue} rows with correct net amounts,
 *       and their sum equals the {@code consolidated_revenue} total.
 *   <li>Re-delivering the same event (same event id) does not double-count the outlet accumulator
 *       (idempotency via {@code processOnce}).
 *   <li>Multiple postings to the same outlet accumulate into one row.
 * </ol>
 */
@SpringBootTest
class OutletRevenueWriterTest extends PostgresRlsTestBase {

  private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final UUID OUTLET_1 = UUID.fromString("b1b1b1b1-b1b1-b1b1-b1b1-b1b1b1b1b1b1");
  private static final UUID OUTLET_2 = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2");
  private static final Instant OCCURRED = Instant.parse("2026-07-01T09:00:00Z");
  private static final String PERIOD = "2026-07";
  private static final String ACTOR = "cashier@test.co.id";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private RevenueReader revenueReader;
  @Autowired private OutletRevenueReader outletRevenueReader;

  @Test
  void twoOutletsProduceTwoRowsAndSumEqualsConsolidated() throws Exception {
    long amount1 = 100_000L;
    long amount2 = 250_000L;

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_1, Money.ofMinor(amount1, "IDR"), OCCURRED));
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_2, Money.ofMinor(amount2, "IDR"), OCCURRED));

    // Two distinct outlet_revenue rows must exist for the tenant.
    long outletRowCount = outletRowCountAsAdmin(TENANT, PERIOD);
    assertThat(outletRowCount).as("two distinct outlets must produce two rows").isEqualTo(2L);

    // Each row must carry the correct net amount.
    long outlet1Revenue = outletRevenueMinorAsAdmin(TENANT, OUTLET_1, PERIOD);
    long outlet2Revenue = outletRevenueMinorAsAdmin(TENANT, OUTLET_2, PERIOD);
    assertThat(outlet1Revenue).as("outlet 1 revenue").isEqualTo(amount1);
    assertThat(outlet2Revenue).as("outlet 2 revenue").isEqualTo(amount2);

    // Sum of outlet rows equals consolidated_revenue total (Σ outlets == consolidated).
    Optional<Money> consolidated =
        TenantContext.callAs(TENANT, ACTOR, () -> revenueReader.revenueForPeriod(PERIOD));
    assertThat(consolidated).isPresent();
    assertThat(outlet1Revenue + outlet2Revenue)
        .as("Σ outlet revenues must equal consolidated revenue")
        .isEqualTo(consolidated.get().amountMinor());

    // The reader also returns the correct breakdown (projection→DTO mapping).
    Optional<OutletRevenueResponse> readResponse =
        TenantContext.callAs(TENANT, ACTOR, () -> outletRevenueReader.outletsForPeriod(PERIOD));
    assertThat(readResponse).isPresent();
    assertThat(readResponse.get().outlets()).hasSize(2);
    // Rows are ordered by revenue desc — outlet2 (250k) first.
    assertThat(readResponse.get().outlets().getFirst().revenueMinor()).isEqualTo(amount2);
    assertThat(readResponse.get().outlets().getLast().revenueMinor()).isEqualTo(amount1);
  }

  @Test
  void redeliveringTheSameEventDoesNotDoubleCountTheOutletAccumulator() throws Exception {
    long amount = 75_000L;
    UUID eventId = UUID.randomUUID();
    SaleRecordedEvent event =
        new SaleRecordedEvent(eventId, TENANT, OUTLET_1, Money.ofMinor(amount, "IDR"), OCCURRED);

    boolean first = revenuePostingService.handle(event);
    boolean second = revenuePostingService.handle(event); // re-delivery

    assertThat(first).isTrue();
    assertThat(second).as("re-delivery must be skipped (idempotent)").isFalse();

    // The outlet accumulator must reflect exactly ONE posting.
    long outletRevenue = outletRevenueMinorAsAdmin(TENANT, OUTLET_1, PERIOD);
    assertThat(outletRevenue).as("outlet accumulator must not be double-counted").isEqualTo(amount);

    // And only one outlet_revenue row for this outlet exists.
    long outletRowCount = outletRowCountAsAdmin(TENANT, PERIOD);
    assertThat(outletRowCount).as("exactly one outlet row").isEqualTo(1L);
  }

  @Test
  void multiplePostingsToTheSameOutletAccumulateIntoOneRow() throws Exception {
    long sale1 = 50_000L;
    long sale2 = 30_000L;

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_1, Money.ofMinor(sale1, "IDR"), OCCURRED));
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_1, Money.ofMinor(sale2, "IDR"), OCCURRED));

    // Same outlet: one row, both amounts accumulated.
    long outletRowCount = outletRowCountAsAdmin(TENANT, PERIOD);
    assertThat(outletRowCount).as("same outlet: one row").isEqualTo(1L);

    long accumulated = outletRevenueMinorAsAdmin(TENANT, OUTLET_1, PERIOD);
    assertThat(accumulated).as("two sales on same outlet must accumulate").isEqualTo(sale1 + sale2);
  }

  // ------------------------------------------------------------------ helpers

  private long outletRowCountAsAdmin(String tenantId, String period) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM outlet_revenue"
                    + " WHERE company_id = '"
                    + tenantId
                    + "' AND period = '"
                    + period
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long outletRevenueMinorAsAdmin(String tenantId, UUID businessId, String period)
      throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(revenue_minor, 0) FROM outlet_revenue"
                    + " WHERE company_id = ? AND business_id = ? AND period = ?")) {
      ps.setString(1, tenantId);
      ps.setObject(2, businessId);
      ps.setString(3, period);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
  }
}
