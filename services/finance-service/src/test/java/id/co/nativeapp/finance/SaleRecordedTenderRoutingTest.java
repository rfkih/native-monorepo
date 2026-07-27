package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the GL clearing account routes correctly by {@code tender_type} (ADR 0006, slice
 * 2).
 *
 * <p>The SALE template (V13) always debits {@code CASH_CLEARING} / credits {@code REVENUE}. When a
 * {@code SaleRecorded} event carries a non-null {@code tender_type}, the debit line must instead
 * route to the correct digital clearing account:
 *
 * <ul>
 *   <li>{@code null} / {@code "CASH"} → account {@code 1900} (CASH_CLEARING — unchanged default,
 *       carwash / legacy unaffected)
 *   <li>{@code "QRIS"} → account {@code 1901} (QRIS_CLEARING, V15)
 *   <li>{@code "CARD"} → account {@code 1902} (CARD_CLEARING, V15)
 * </ul>
 *
 * <p>Each scenario also asserts the journal entry is balanced (Σdebit == Σcredit).
 */
@SpringBootTest
class SaleRecordedTenderRoutingTest extends PostgresRlsTestBase {

  private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
  private static final UUID BUSINESS = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
  private static final Instant OCCURRED = Instant.parse("2026-06-14T10:00:00Z");
  private static final long AMOUNT_IDR = 50_000L;

  @Autowired private RevenuePostingService revenuePostingService;

  @Test
  void nullTenderRoutesToCashClearing() throws Exception {
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            eventId,
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            null)); // null → CASH_CLEARING

    String debitAccount = debitAccountForEvent(eventId);
    assertThat(debitAccount)
        .as("null tender_type must route to CASH_CLEARING (1900)")
        .isEqualTo("1900");
    assertEntryIsBalanced(eventId);
  }

  @Test
  void cashTenderRoutesToCashClearing() throws Exception {
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(AMOUNT_IDR, "IDR"), OCCURRED, "CASH"));

    String debitAccount = debitAccountForEvent(eventId);
    assertThat(debitAccount)
        .as("CASH tender_type must route to CASH_CLEARING (1900)")
        .isEqualTo("1900");
    assertEntryIsBalanced(eventId);
  }

  @Test
  void qrisTenderRoutesToQrisClearing() throws Exception {
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(AMOUNT_IDR, "IDR"), OCCURRED, "QRIS"));

    String debitAccount = debitAccountForEvent(eventId);
    assertThat(debitAccount)
        .as("QRIS tender_type must route to QRIS_CLEARING (1901)")
        .isEqualTo("1901");
    assertEntryIsBalanced(eventId);
  }

  @Test
  void cardTenderRoutesToCardClearing() throws Exception {
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            eventId, TENANT, BUSINESS, Money.ofMinor(AMOUNT_IDR, "IDR"), OCCURRED, "CARD"));

    String debitAccount = debitAccountForEvent(eventId);
    assertThat(debitAccount)
        .as("CARD tender_type must route to CARD_CLEARING (1902)")
        .isEqualTo("1902");
    assertEntryIsBalanced(eventId);
  }

  @Test
  void unknownTenderFallsBackToCashClearing() throws Exception {
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        new SaleRecordedEvent(
            eventId,
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CHEQUE")); // unknown → safe default

    String debitAccount = debitAccountForEvent(eventId);
    assertThat(debitAccount)
        .as("unknown tender_type must fall back to CASH_CLEARING (1900) — money never dropped")
        .isEqualTo("1900");
    assertEntryIsBalanced(eventId);
  }

  // ------------------------------------------------------------------ assertion helpers

  /**
   * Returns the account_code of the DEBIT line for the journal_entry whose source_event_id matches
   * the given event id. Reads over the admin (BYPASSRLS) connection so no GUC is required.
   */
  private String debitAccountForEvent(UUID eventId) throws Exception {
    String sql =
        "SELECT jl.account_code"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + eventId
            + "'"
            + "   AND jl.debit_minor > 0";
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next())
          .as("journal_line DEBIT row must exist for eventId %s", eventId)
          .isTrue();
      return rs.getString("account_code");
    }
  }

  /** Asserts Σdebit == Σcredit for the journal entry produced by the given event. */
  private void assertEntryIsBalanced(UUID eventId) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + eventId
            + "'";
    long totalDebit = 0L;
    long totalCredit = 0L;
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        totalDebit += rs.getLong("debit_minor");
        totalCredit += rs.getLong("credit_minor");
        rows.add("debit=" + rs.getLong("debit_minor") + " credit=" + rs.getLong("credit_minor"));
      }
      assertThat(rows).as("journal must have at least 2 lines").hasSizeGreaterThanOrEqualTo(2);
    }
    assertThat(totalDebit)
        .as("journal entry must be balanced (Σdebit == Σcredit) for eventId %s", eventId)
        .isEqualTo(totalCredit);
    assertThat(totalDebit).as("total must be positive").isPositive();
  }

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
      // Tables not yet created — nothing to reset.
    }
  }
}
