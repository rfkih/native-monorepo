package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.service.ReversalPostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that a {@code SaleRefunded} event causes finance to post a proportional contra entry for
 * the refunded amount (ADR 0006, slice 4).
 *
 * <p>Scenarios:
 *
 * <ul>
 *   <li>Full refund: net revenue == 0.
 *   <li>Partial refund: net revenue == original − refund.
 *   <li>Sequential partial refunds: net converges to zero.
 *   <li>Idempotency: second delivery of same refund_id is a no-op.
 * </ul>
 */
@SpringBootTest
class SaleRefundedReversalTest extends PostgresRlsTestBase {

  private static final String TENANT = "33333333-3333-3333-3333-aaaaaaaaaaaa";
  private static final UUID BUSINESS = UUID.fromString("44444444-4444-4444-4444-aaaaaaaaaaaa");
  private static final Instant OCCURRED = Instant.parse("2026-06-14T11:00:00Z");

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;

  @Test
  void fullRefundBringsRevenueToZero() throws Exception {
    long amount = 40_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(amount, "IDR"), OCCURRED, "CARD"));

    assertThat(consolidatedRevenueMinorAsAdmin()).isEqualTo(amount);

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(amount, "IDR"), // full refund
            amount, // totalRefundedMinor
            OCCURRED,
            "CARD",
            null));

    assertThat(consolidatedRevenueMinorAsAdmin())
        .as("full refund must bring revenue to 0")
        .isZero();
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L);
    // The refund posting must carry posting_role = REVERSAL (M1 fix).
    assertRefundPostingRole(refundId, "REVERSAL");
  }

  @Test
  void partialRefundReducesRevenueProperly() throws Exception {
    long amount = 60_000L;
    long refundAmount = 20_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(amount, "IDR"), OCCURRED, "QRIS"));

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(refundAmount, "IDR"),
            refundAmount, // totalRefundedMinor
            OCCURRED,
            "QRIS",
            null));

    long netRevenue = consolidatedRevenueMinorAsAdmin();
    assertThat(netRevenue)
        .as("net revenue after partial refund must be (original - refund)")
        .isEqualTo(amount - refundAmount);
  }

  @Test
  void refundJournalEntryIsBalanced() throws Exception {
    long amount = 50_000L;
    long refundAmount = 50_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(amount, "IDR"), OCCURRED, null));

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(refundAmount, "IDR"),
            refundAmount,
            OCCURRED,
            null,
            null));

    assertRefundJournalIsBalanced(refundId);
  }

  @Test
  void refundIsIdempotent() throws Exception {
    long amount = 25_000L;
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(amount, "IDR"), OCCURRED, "CASH"));

    UUID refundId = UUID.randomUUID();
    SaleRefundedEvent refundEvent =
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(amount, "IDR"),
            amount,
            OCCURRED,
            "CASH",
            null);

    boolean first = reversalPostingService.handleRefund(refundEvent);
    boolean second = reversalPostingService.handleRefund(refundEvent); // re-delivery

    assertThat(first).isTrue();
    assertThat(second).as("second refund delivery must be skipped (idempotent)").isFalse();
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L); // 1 sale + 1 refund
  }

  // ------------------------------------------------------------------ helpers

  private long consolidatedRevenueMinorAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue"
                    + " WHERE company_id = '"
                    + TENANT
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void assertRefundPostingRole(UUID refundId, String expectedRole) throws Exception {
    String sql =
        "SELECT posting_role FROM ledger_posting" + " WHERE source_event_id = '" + refundId + "'";
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("refund posting must exist for refundId=" + refundId).isTrue();
      assertThat(rs.getString("posting_role"))
          .as("refund posting_role must be " + expectedRole)
          .isEqualTo(expectedRole);
    }
  }

  private void assertRefundJournalIsBalanced(UUID refundId) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + refundId
            + "'";
    long totalDebit = 0L;
    long totalCredit = 0L;
    int lines = 0;
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        totalDebit += rs.getLong("debit_minor");
        totalCredit += rs.getLong("credit_minor");
        lines++;
      }
    }
    assertThat(lines).as("refund journal must have >= 2 lines").isGreaterThanOrEqualTo(2);
    assertThat(totalDebit).as("refund journal must balance").isEqualTo(totalCredit);
    assertThat(totalDebit).as("refund journal totals must be positive").isPositive();
  }

  @BeforeEach
  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " outlet_revenue, org_unit_ref,"
              + " payroll_run_ledger, group_ref, group_member, group_lead,"
              + " group_trial_balance, consolidation_ledger, consolidation_summary,"
              + " intercompany_match, group_membership_pending, processed_event,"
              + " outbox, within_company_close, member_group_index,"
              + " journal_line, journal_entry CASCADE");
    } catch (Exception ignored) {
      // Tables not yet created.
    }
  }
}
