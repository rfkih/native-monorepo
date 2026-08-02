package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.revenue.service.RevenueReader;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.finance.reversal.service.ReversalPostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the {@code outlet_revenue} accumulator is correctly unwound by {@code SaleVoided}
 * and {@code SaleRefunded} reversals, and that the outlet slice stays reconciled with the
 * consolidated revenue total after each reversal.
 *
 * <p>Acceptance criteria:
 *
 * <ol>
 *   <li>A void brings the outlet accumulator to zero and the outlet total still equals
 *       consolidated.
 *   <li>A full refund brings the outlet accumulator to zero and reconciles with consolidated.
 *   <li>Σ outlet revenues == consolidated revenue after each operation.
 * </ol>
 */
@SpringBootTest
class OutletRevenueReversalTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final UUID OUTLET = UUID.fromString("d1d1d1d1-d1d1-d1d1-d1d1-d1d1d1d1d1d1");
  private static final Instant OCCURRED = Instant.parse("2026-07-01T10:00:00Z");
  private static final String PERIOD = "2026-07";
  private static final String ACTOR = "cashier@reversal.co.id";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;
  @Autowired private RevenueReader revenueReader;

  @Test
  void voidUnwindsOutletAccumulatorAndReconciles() throws Exception {
    long amount = 120_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, OUTLET, Money.ofMinor(amount, "IDR"), OCCURRED, "CASH"));

    assertThat(outletRevenueMinorAsAdmin(TENANT, OUTLET, PERIOD))
        .as("outlet accumulator before void")
        .isEqualTo(amount);

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            OUTLET,
            UUID.randomUUID(), // saleId
            UUID.randomUUID(), // paymentId
            Money.ofMinor(amount, "IDR"),
            OCCURRED,
            "CASH", null));

    // Outlet accumulator must be wound back to zero.
    long outletAfter = outletRevenueMinorAsAdmin(TENANT, OUTLET, PERIOD);
    assertThat(outletAfter).as("void must unwind outlet accumulator to 0").isZero();

    // Consolidated revenue must also be zero.
    Optional<Money> consolidated =
        TenantContext.callAs(TENANT, ACTOR, () -> revenueReader.revenueForPeriod(PERIOD));
    assertThat(consolidated.map(Money::amountMinor).orElse(0L))
        .as("consolidated revenue must also be 0 after void")
        .isZero();
  }

  @Test
  void fullRefundUnwindsOutletAccumulatorAndReconciles() throws Exception {
    long amount = 80_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, OUTLET, Money.ofMinor(amount, "IDR"), OCCURRED, "QRIS"));

    assertThat(outletRevenueMinorAsAdmin(TENANT, OUTLET, PERIOD)).isEqualTo(amount);

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            OUTLET,
            UUID.randomUUID(), // saleId
            UUID.randomUUID(), // paymentId
            Money.ofMinor(amount, "IDR"), // full refund
            amount, // totalRefundedMinor
            OCCURRED,
            "QRIS", null));

    // Outlet accumulator must be wound back to zero.
    long outletAfter = outletRevenueMinorAsAdmin(TENANT, OUTLET, PERIOD);
    assertThat(outletAfter).as("full refund must unwind outlet accumulator to 0").isZero();

    // Consolidated revenue must also be zero.
    Optional<Money> consolidated =
        TenantContext.callAs(TENANT, ACTOR, () -> revenueReader.revenueForPeriod(PERIOD));
    assertThat(consolidated.map(Money::amountMinor).orElse(0L))
        .as("consolidated revenue must also be 0 after full refund")
        .isZero();
  }

  @Test
  void voidOfOneOutletLeavesOtherOutletIntact() throws Exception {
    UUID outletA = UUID.fromString("e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1");
    UUID outletB = UUID.fromString("e2e2e2e2-e2e2-e2e2-e2e2-e2e2e2e2e2e2");
    long amountA = 50_000L;
    long amountB = 90_000L;

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, outletA, Money.ofMinor(amountA, "IDR"), OCCURRED));
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, outletB, Money.ofMinor(amountB, "IDR"), OCCURRED));

    // Void only outlet A.
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            UUID.randomUUID(),
            TENANT,
            outletA,
            UUID.randomUUID(),
            UUID.randomUUID(),
            Money.ofMinor(amountA, "IDR"),
            OCCURRED,
            null, null));

    // Outlet A is zero; outlet B is untouched.
    assertThat(outletRevenueMinorAsAdmin(TENANT, outletA, PERIOD))
        .as("voided outlet A must be 0")
        .isZero();
    assertThat(outletRevenueMinorAsAdmin(TENANT, outletB, PERIOD))
        .as("outlet B must remain unchanged")
        .isEqualTo(amountB);

    // Consolidated revenue must equal outlet B only.
    Optional<Money> consolidated =
        TenantContext.callAs(TENANT, ACTOR, () -> revenueReader.revenueForPeriod(PERIOD));
    assertThat(consolidated.map(Money::amountMinor).orElse(0L))
        .as("consolidated after void of A must equal outlet B")
        .isEqualTo(amountB);
  }

  // ------------------------------------------------------------------ helpers

  private long outletRevenueMinorAsAdmin(String tenantId, UUID businessId, String period)
      throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
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
