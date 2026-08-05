package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.finance.reversal.service.ReversalPostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * QA sweep 2026-08-05 HIGH regression (the finance twin of loyalty's {@code
 * SaleReversalReorderTest}): a {@code SaleVoided}/{@code SaleRefunded} consumed BEFORE its {@code
 * SaleRecorded} posted used to take the GROSS template fall-back — structurally wrong for a sale
 * with tax/service-charge legs (and the real per-leg SALE entry then posted on top). Now a
 * saleId-carrying reversal with no posted SALE entry PARKS in {@code pending_sale_reversal}; {@code
 * RevenuePostingWriter} applies it per-leg in the SAME transaction the sale posts in. A parked
 * PARTIAL refund resolves REJECTED_PARTIAL (the live path's 400-equivalent) without touching the
 * sale posting.
 */
@SpringBootTest
class ReversalReorderParkingTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-2222-3333-4444-555555555555";
  private static final UUID BUSINESS = UUID.fromString("66666666-7777-8888-9999-000000000000");
  private static final Instant OCCURRED = Instant.parse("2026-07-20T10:00:00Z");

  // The Phase 2 breakdown shape (tax + service charge) the GROSS fall-back would misbook.
  private static final long SUBTOTAL_MINOR = 30_000L;
  private static final long DISCOUNT_MINOR = 0L;
  private static final long SERVICE_CHARGE_MINOR = 1_500L;
  private static final long TAX_MINOR = 3_150L;
  private static final long GRAND_TOTAL_MINOR = 34_650L;

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;

  @Test
  void voidBeforeSaleParksNothingPostsThenAppliesPerLegWhenTheSaleArrives() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID voidId = UUID.randomUUID();

    // T1: the void arrives FIRST (cross-topic reorder). It must park — and post NOTHING.
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
            OCCURRED,
            "CASH",
            null));

    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND applied_at IS NULL",
                saleId))
        .isEqualTo(1L);
    assertThat(scalar("SELECT COUNT(*) FROM ledger_posting WHERE source_event_id = ?", voidId))
        .as("a parked void must post NO ledger rows")
        .isZero();
    assertThat(scalar("SELECT COUNT(*) FROM journal_entry WHERE source_event_id = ?", voidId))
        .isZero();
    assertThat(consolidatedRevenue()).isZero();

    // T2: the sale posts. The parked void must apply PER-LEG in the same transaction:
    // consolidated revenue nets to zero and the SALE_VOID journal is balanced.
    revenuePostingService.handle(phase2SaleEvent(UUID.randomUUID(), saleId));

    assertThat(consolidatedRevenue()).as("sale + applied void must net to zero").isZero();
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND outcome = 'APPLIED'",
                saleId))
        .isEqualTo(1L);
    assertThat(scalar("SELECT COUNT(*) FROM journal_entry WHERE source_event_id = ?", voidId))
        .isEqualTo(1L);
    assertVoidJournalBalanced(voidId);
    assertThat(scalar("SELECT COUNT(*) FROM ledger_posting WHERE company_id = ?", TENANT))
        .as("one SALE posting + one contra")
        .isEqualTo(2L);
  }

  @Test
  void parkedPartialRefundResolvesRejectedWithoutPoisoningTheSalePosting() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID refundId = UUID.randomUUID();

    // A PARTIAL refund (10_000 < grand 34_650) arrives before the sale — parks.
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(10_000L, "IDR"),
            10_000L,
            OCCURRED,
            "CASH",
            null));
    assertThat(scalar("SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?", saleId))
        .isEqualTo(1L);

    // The sale posts — it must SUCCEED (net revenue stands); the parked partial refund resolves
    // REJECTED_PARTIAL (the live path's 400-equivalent) with no contra posted.
    revenuePostingService.handle(phase2SaleEvent(UUID.randomUUID(), saleId));

    assertThat(consolidatedRevenue())
        .as("the sale posting must survive; no unwind for a rejected partial refund")
        .isEqualTo(SUBTOTAL_MINOR - DISCOUNT_MINOR);
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND outcome = 'REJECTED_PARTIAL'",
                saleId))
        .isEqualTo(1L);
    assertThat(scalar("SELECT COUNT(*) FROM journal_entry WHERE source_event_id = ?", refundId))
        .isZero();
  }

  @Test
  void fullRefundBeforeSaleParksThenAppliesPerLeg() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID refundId = UUID.randomUUID();

    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
            GRAND_TOTAL_MINOR,
            OCCURRED,
            "CASH",
            null));
    assertThat(scalar("SELECT COUNT(*) FROM journal_entry WHERE source_event_id = ?", refundId))
        .isZero();

    revenuePostingService.handle(phase2SaleEvent(UUID.randomUUID(), saleId));

    assertThat(consolidatedRevenue()).as("sale + applied full refund must net to zero").isZero();
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND outcome = 'APPLIED'",
                saleId))
        .isEqualTo(1L);
    assertVoidJournalBalanced(refundId);
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private SaleRecordedEvent phase2SaleEvent(UUID eventId, UUID saleId) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        BUSINESS,
        Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
        OCCURRED,
        "CASH",
        SUBTOTAL_MINOR,
        DISCOUNT_MINOR,
        SERVICE_CHARGE_MINOR,
        TAX_MINOR,
        "v1-illustrative",
        true);
  }

  // ── verification (admin/BYPASSRLS JDBC) ─────────────────────────────────────

  private long consolidatedRevenue() throws Exception {
    return scalar(
        "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue WHERE company_id = ?",
        TENANT);
  }

  private void assertVoidJournalBalanced(UUID sourceEventId) throws Exception {
    long debit =
        scalar(
            "SELECT COALESCE(sum(jl.debit_minor), 0) FROM journal_entry je"
                + " JOIN journal_line jl ON jl.entry_id = je.id WHERE je.source_event_id = ?",
            sourceEventId);
    long credit =
        scalar(
            "SELECT COALESCE(sum(jl.credit_minor), 0) FROM journal_entry je"
                + " JOIN journal_line jl ON jl.entry_id = je.id WHERE je.source_event_id = ?",
            sourceEventId);
    assertThat(debit).as("contra journal must balance").isEqualTo(credit).isPositive();
  }

  private long scalar(String sql, Object... params) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i] instanceof String s ? s : params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
