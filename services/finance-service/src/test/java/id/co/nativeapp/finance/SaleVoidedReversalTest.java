package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
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
 * Verifies that a {@code SaleVoided} event causes finance to fully reverse the original {@code
 * SaleRecorded} ledger posting (ADR 0006, slice 4).
 *
 * <p>Scenario:
 *
 * <ol>
 *   <li>Post a {@code SaleRecorded} → ledger_posting (REVENUE, positive), consolidated_revenue
 *       accumulates, P&amp;L revenue leg accumulates, SALE journal entry posted.
 *   <li>Post a {@code SaleVoided} for the same sale → contra ledger_posting (REVENUE, negative),
 *       consolidated_revenue decrements, P&amp;L revenue leg decrements, SALE_VOID contra journal
 *       entry posted (Dr REVENUE / Cr CLEARING).
 *   <li>Net: consolidated_revenue.total_minor == 0; both GL entries balance.
 * </ol>
 */
@SpringBootTest
class SaleVoidedReversalTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-aaaaaaaaaaaa";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-aaaaaaaaaaaa");
  private static final Instant OCCURRED = Instant.parse("2026-06-14T10:00:00Z");
  private static final long AMOUNT_IDR = 30_000L;

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;

  @Test
  void voidFullyReversesRevenue() throws Exception {
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(AMOUNT_IDR, "IDR"), OCCURRED, "CASH"));

    long revenueBefore = consolidatedRevenueMinorAsAdmin(TENANT);
    assertThat(revenueBefore).isEqualTo(AMOUNT_IDR);

    // Post the void.
    UUID voidId = UUID.randomUUID();
    // saleId NULL = a genuinely id-less legacy event, which keeps this test on the GROSS
    // fall-back path it asserts. A NON-null saleId with no posted SALE entry now PARKS instead
    // (cross-topic reorder fix, QA sweep 2026-08-05) — see ReversalReorderParkingTest.
    UUID saleId = null;
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(), // paymentId
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CASH",
            null));

    // consolidated_revenue must have been decremented back to 0.
    long revenueAfter = consolidatedRevenueMinorAsAdmin(TENANT);
    assertThat(revenueAfter).as("void must fully reverse revenue to 0").isZero();

    // Two ledger_posting rows (one positive, one negative).
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L);

    // The reversal posting must carry posting_role = REVERSAL (M1 fix).
    assertReversalPostingRole(voidId, "REVERSAL");

    // SALE_VOID journal entry must be balanced.
    assertVoidJournalIsBalanced(voidId);
  }

  @Test
  void voidJournalEntryHasCorrectDebitOnRevenue() throws Exception {
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(20_000L, "IDR"), OCCURRED, "QRIS"));

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(20_000L, "IDR"),
            OCCURRED,
            "QRIS",
            null));

    // The SALE_VOID debit line must route to REVENUE (4000), not CLEARING.
    String debitAccount = debitAccountForVoidEntry(voidId);
    assertThat(debitAccount)
        .as("SALE_VOID debit must be on REVENUE account (4000)")
        .isEqualTo("4000");
  }

  @Test
  void voidIsIdempotent() throws Exception {
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(15_000L, "IDR"), OCCURRED, null));

    UUID voidId = UUID.randomUUID();
    SaleVoidedEvent voidEvent =
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            null, // saleId: legacy id-less event — stays on the GROSS fall-back this test asserts
            UUID.randomUUID(),
            Money.ofMinor(15_000L, "IDR"),
            OCCURRED,
            null,
            null);

    boolean firstPosting = reversalPostingService.handleVoid(voidEvent);
    boolean secondPosting = reversalPostingService.handleVoid(voidEvent); // re-delivery

    assertThat(firstPosting).isTrue();
    assertThat(secondPosting).as("re-delivery must be skipped (idempotent)").isFalse();
    // Still only 2 ledger_posting rows (1 sale + 1 void).
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L);
  }

  // ------------------------------------------------------------------ helpers

  private long consolidatedRevenueMinorAsAdmin(String tenantId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue"
                    + " WHERE company_id = '"
                    + tenantId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void assertVoidJournalIsBalanced(UUID voidId) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + voidId
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
    assertThat(lines).as("void journal must have at least 2 lines").isGreaterThanOrEqualTo(2);
    assertThat(totalDebit)
        .as("void journal must balance (Σdebit == Σcredit)")
        .isEqualTo(totalCredit);
    assertThat(totalDebit).as("void journal totals must be positive").isPositive();
  }

  private void assertReversalPostingRole(UUID sourceEventId, String expectedRole) throws Exception {
    String sql =
        "SELECT posting_role FROM ledger_posting"
            + " WHERE source_event_id = '"
            + sourceEventId
            + "'";
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next())
          .as("reversal posting must exist for sourceEventId=" + sourceEventId)
          .isTrue();
      assertThat(rs.getString("posting_role"))
          .as("reversal posting_role must be " + expectedRole)
          .isEqualTo(expectedRole);
    }
  }

  private String debitAccountForVoidEntry(UUID voidId) throws Exception {
    String sql =
        "SELECT jl.account_code"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + voidId
            + "'"
            + "   AND jl.debit_minor > 0";
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("void journal DEBIT line must exist").isTrue();
      return rs.getString("account_code");
    }
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
