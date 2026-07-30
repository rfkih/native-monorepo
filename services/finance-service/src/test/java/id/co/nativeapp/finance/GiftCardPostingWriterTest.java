package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.giftcard.messaging.GiftCardSoldEvent;
import id.co.nativeapp.finance.giftcard.service.GiftCardPostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@code GiftCardPostingWriter} (ADR 0027, Phase 4 of the POS-parity program)
 * against the real Flyway-migrated schema — proving V37's illustrative reference data (the {@code
 * GIFT_CARD_SALE} v1 template, role 2500) and this Java change integrate correctly.
 *
 * <p>Covers: the liability posting itself (Dr &lt;tender clearing&gt; / Cr GIFT_CARD_LIABILITY,
 * balanced), tender routing (mirrors {@code SaleRecordedTenderRoutingTest}), idempotency
 * (re-delivery posts exactly once), and — the ADR 0027 decision 4 guarantee — that a gift-card sale
 * touches NO revenue read model ({@code consolidated_revenue}/{@code outlet_revenue}/{@code
 * consolidated_pnl} all stay empty; a gift-card sale is a liability, not income).
 */
@SpringBootTest
class GiftCardPostingWriterTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
  private static final Instant OCCURRED = Instant.parse("2026-08-01T11:00:00Z");
  private static final long AMOUNT_IDR = 100_000L;

  @Autowired private GiftCardPostingService giftCardPostingService;

  @Test
  void cashTenderPostsBalancedLiabilityEntry() throws Exception {
    UUID giftCardSaleId = UUID.randomUUID();
    giftCardPostingService.handle(
        new GiftCardSoldEvent(
            giftCardSaleId,
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CASH"));

    assertThat(debitAccountFor(giftCardSaleId))
        .as("CASH tender must debit CASH_CLEARING (1900)")
        .isEqualTo("1900");
    assertThat(creditAccountFor(giftCardSaleId))
        .as("the credit leg must be GIFT_CARD_LIABILITY (2500, V37)")
        .isEqualTo("2500");
    assertEntryIsBalanced(giftCardSaleId, AMOUNT_IDR);
  }

  @Test
  void nullTenderRoutesToCashClearing() throws Exception {
    UUID giftCardSaleId = UUID.randomUUID();
    giftCardPostingService.handle(
        new GiftCardSoldEvent(
            giftCardSaleId,
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            null));

    assertThat(debitAccountFor(giftCardSaleId))
        .as("null tender_type must fall back to CASH_CLEARING (1900)")
        .isEqualTo("1900");
    assertEntryIsBalanced(giftCardSaleId, AMOUNT_IDR);
  }

  @Test
  void qrisTenderRoutesToQrisClearing() throws Exception {
    UUID giftCardSaleId = UUID.randomUUID();
    giftCardPostingService.handle(
        new GiftCardSoldEvent(
            giftCardSaleId,
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "QRIS"));

    assertThat(debitAccountFor(giftCardSaleId))
        .as("QRIS tender must debit QRIS_CLEARING (1901)")
        .isEqualTo("1901");
    assertEntryIsBalanced(giftCardSaleId, AMOUNT_IDR);
  }

  @Test
  void cardTenderRoutesToCardClearing() throws Exception {
    UUID giftCardSaleId = UUID.randomUUID();
    giftCardPostingService.handle(
        new GiftCardSoldEvent(
            giftCardSaleId,
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CARD"));

    assertThat(debitAccountFor(giftCardSaleId))
        .as("CARD tender must debit CARD_CLEARING (1902)")
        .isEqualTo("1902");
    assertEntryIsBalanced(giftCardSaleId, AMOUNT_IDR);
  }

  @Test
  void reDeliveryIsIdempotent() throws Exception {
    UUID giftCardSaleId = UUID.randomUUID();
    GiftCardSoldEvent event =
        new GiftCardSoldEvent(
            giftCardSaleId,
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CASH");

    boolean first = giftCardPostingService.handle(event);
    boolean second = giftCardPostingService.handle(event); // re-delivery, same gift_card_sale_id

    assertThat(first).isTrue();
    assertThat(second).as("re-delivery must be skipped (idempotent)").isFalse();
    assertThat(journalEntryCountFor(giftCardSaleId))
        .as("exactly one journal_entry row for the duplicated gift_card_sale_id")
        .isEqualTo(1L);
  }

  @Test
  void giftCardSaleDoesNotTouchAnyRevenueReadModel() throws Exception {
    giftCardPostingService.handle(
        new GiftCardSoldEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(AMOUNT_IDR, "IDR"),
            OCCURRED,
            "CASH"));

    assertThat(countAsAdmin("consolidated_revenue"))
        .as("a gift-card sale is a LIABILITY, not revenue — consolidated_revenue must stay empty")
        .isZero();
    assertThat(countAsAdmin("outlet_revenue")).isZero();
    assertThat(countAsAdmin("consolidated_pnl")).isZero();
    assertThat(countAsAdmin("ledger_posting"))
        .as("no dimensional ledger_posting row either — only the GL journal entry is written")
        .isZero();
  }

  // ------------------------------------------------------------------ helpers

  private String debitAccountFor(UUID giftCardSaleId) throws Exception {
    return accountFor(giftCardSaleId, true);
  }

  private String creditAccountFor(UUID giftCardSaleId) throws Exception {
    return accountFor(giftCardSaleId, false);
  }

  private String accountFor(UUID giftCardSaleId, boolean debit) throws Exception {
    String column = debit ? "jl.debit_minor" : "jl.credit_minor";
    String sql =
        "SELECT jl.account_code"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + giftCardSaleId
            + "'"
            + "   AND "
            + column
            + " > 0";
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next())
          .as(
              (debit ? "debit" : "credit")
                  + " line must exist for giftCardSaleId="
                  + giftCardSaleId)
          .isTrue();
      return rs.getString("account_code");
    }
  }

  private void assertEntryIsBalanced(UUID giftCardSaleId, long expectedTotal) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + giftCardSaleId
            + "'";
    long totalDebit = 0L;
    long totalCredit = 0L;
    int lines = 0;
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        totalDebit += rs.getLong("debit_minor");
        totalCredit += rs.getLong("credit_minor");
        lines++;
      }
    }
    assertThat(lines).as("GIFT_CARD_SALE entry must have exactly 2 lines").isEqualTo(2);
    assertThat(totalDebit).as("Σdebit must equal Σcredit").isEqualTo(totalCredit);
    assertThat(totalDebit)
        .as("total must equal the gift-card sale amount")
        .isEqualTo(expectedTotal);
  }

  private long journalEntryCountFor(UUID giftCardSaleId) throws Exception {
    String sql =
        "SELECT count(*) FROM journal_entry WHERE source_event_id = '" + giftCardSaleId + "'";
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long countAsAdmin(String table) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
