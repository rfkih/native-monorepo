package id.co.nativeapp.restaurant.sale;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the V39 sale-breakdown snapshot: {@link
 * id.co.nativeapp.restaurant.sale.service.SaleWriter#stampBreakdownIfPresent} persists the Phase 2
 * price breakdown onto the {@code sale} row at ring time, {@code discount_minor} is decomposed
 * PROMO-ONLY when a loyalty redemption is present, and a legacy/no-breakdown caller leaves every
 * V39 column {@code NULL} (the daily-summary reader's documented COALESCE fallback target).
 */
@SpringBootTest
class SaleBreakdownSnapshotIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-breakdown-snapshot@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private SaleService saleService;

  private static <T> T asTenant(Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void ringingASaleWithABreakdownPersistsAllSixColumnsOnTheSaleRow() throws Exception {
    // subtotal 100,000 - discount 10,000 + serviceCharge 5,000 + tax 9,500 = 104,500 grandTotal.
    // No loyalty redemption here, so discount_minor is the FULL (already promo-only) 10,000.
    PriceBreakdown breakdown =
        breakdown(100_000L, 10_000L, 5_000L, 9_500L, "IDR", true /* illustrative */);
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET,
            breakdown.grandTotal().amountMinor(),
            "IDR",
            Instant.now(),
            "breakdown-snapshot-" + UUID.randomUUID(),
            "CASH",
            breakdown,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    UUID saleId = asTenant(() -> saleService.recordSale(command)).sale().id();

    SaleBreakdownRow row = readBreakdownRow(saleId);
    assertThat(row.subtotalMinor).isEqualTo(100_000L);
    assertThat(row.discountMinor).isEqualTo(10_000L);
    assertThat(row.serviceChargeMinor).isEqualTo(5_000L);
    assertThat(row.taxMinor).isEqualTo(9_500L);
    assertThat(row.loyaltyRedeemedMinor).isEqualTo(0L);
    assertThat(row.usesIllustrativeRules).isTrue();
  }

  @Test
  void discountMinorIsPromoOnlyWhenALoyaltyRedemptionIsPresent() throws Exception {
    // breakdown.discount() is the COMBINED promo+loyalty deduction on the wire (RecordSaleCommand
    // javadoc): promo-only 3,000 + loyalty 2,000 = combined 5,000. stampBreakdownIfPresent must
    // persist discount_minor as the PROMO-ONLY 3,000 (5,000 - 2,000), mirroring
    // SaleRecordedSchema#toRecord's decomposition exactly.
    long combinedDiscount = 5_000L;
    long loyaltyRedeemedMinor = 2_000L;
    PriceBreakdown breakdown = breakdown(40_000L, combinedDiscount, 1_750L, 3_675L, "IDR", false);
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET,
            breakdown.grandTotal().amountMinor(),
            "IDR",
            Instant.now(),
            "breakdown-loyalty-" + UUID.randomUUID(),
            "QRIS",
            breakdown,
            UUID.randomUUID(),
            200L,
            loyaltyRedeemedMinor,
            null,
            null,
            null,
            null,
            null,
            null);

    UUID saleId = asTenant(() -> saleService.recordSale(command)).sale().id();

    SaleBreakdownRow row = readBreakdownRow(saleId);
    assertThat(row.subtotalMinor).isEqualTo(40_000L);
    assertThat(row.discountMinor)
        .as("discount_minor is PROMO-ONLY: combined 5,000 - loyalty 2,000")
        .isEqualTo(3_000L);
    assertThat(row.loyaltyRedeemedMinor).isEqualTo(2_000L);
    assertThat(row.serviceChargeMinor).isEqualTo(1_750L);
    assertThat(row.taxMinor).isEqualTo(3_675L);
    assertThat(row.usesIllustrativeRules).isFalse();
  }

  @Test
  void aLegacySaleWithNoBreakdownLeavesAllSixColumnsNull() throws Exception {
    // The legacy POST /api/v1/sales shape: no tenderType, no breakdown (command.breakdown() ==
    // null). SaleWriter#stampBreakdownIfPresent must no-op, leaving every V39 column NULL so the
    // daily-summary reader's COALESCE(subtotal_minor, amount_minor) fallback applies.
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET, 25_000L, "IDR", Instant.now(), "breakdown-legacy-" + UUID.randomUUID());

    UUID saleId = asTenant(() -> saleService.recordSale(command)).sale().id();

    SaleBreakdownRow row = readBreakdownRow(saleId);
    assertThat(row.subtotalMinor).isNull();
    assertThat(row.discountMinor).isNull();
    assertThat(row.serviceChargeMinor).isNull();
    assertThat(row.taxMinor).isNull();
    assertThat(row.loyaltyRedeemedMinor).isNull();
    assertThat(row.usesIllustrativeRules).isNull();
  }

  /** Builds a {@link PriceBreakdown} whose reconciliation identity holds by construction. */
  private static PriceBreakdown breakdown(
      long subtotal,
      long discount,
      long serviceCharge,
      long tax,
      String currency,
      boolean illustrative) {
    long taxableBase = subtotal - discount;
    long grandTotal = taxableBase + serviceCharge + tax;
    return new PriceBreakdown(
        Money.ofMinor(subtotal, currency),
        Money.ofMinor(discount, currency),
        Money.ofMinor(taxableBase, currency),
        Money.ofMinor(serviceCharge, currency),
        Money.ofMinor(tax, currency),
        Money.ofMinor(grandTotal, currency),
        "test-rule-v1",
        illustrative);
  }

  /** A plain (non-record) row holder — direct public-field access keeps the assertions terse. */
  private static final class SaleBreakdownRow {
    final Long subtotalMinor;
    final Long discountMinor;
    final Long serviceChargeMinor;
    final Long taxMinor;
    final Long loyaltyRedeemedMinor;
    final Boolean usesIllustrativeRules;

    SaleBreakdownRow(
        Long subtotalMinor,
        Long discountMinor,
        Long serviceChargeMinor,
        Long taxMinor,
        Long loyaltyRedeemedMinor,
        Boolean usesIllustrativeRules) {
      this.subtotalMinor = subtotalMinor;
      this.discountMinor = discountMinor;
      this.serviceChargeMinor = serviceChargeMinor;
      this.taxMinor = taxMinor;
      this.loyaltyRedeemedMinor = loyaltyRedeemedMinor;
      this.usesIllustrativeRules = usesIllustrativeRules;
    }
  }

  /** Reads the six V39 columns back over the admin/BYPASSRLS connection. */
  private SaleBreakdownRow readBreakdownRow(UUID saleId) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT subtotal_minor, discount_minor, service_charge_minor, tax_minor,"
                    + " loyalty_redeemed_minor, uses_illustrative_rules FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("the sale row exists").isTrue();
        Long subtotal = (Long) rs.getObject("subtotal_minor");
        Long discount = (Long) rs.getObject("discount_minor");
        Long serviceCharge = (Long) rs.getObject("service_charge_minor");
        Long tax = (Long) rs.getObject("tax_minor");
        Long loyaltyRedeemed = (Long) rs.getObject("loyalty_redeemed_minor");
        Boolean illustrative = (Boolean) rs.getObject("uses_illustrative_rules");
        return new SaleBreakdownRow(
            subtotal, discount, serviceCharge, tax, loyaltyRedeemed, illustrative);
      }
    }
  }
}
