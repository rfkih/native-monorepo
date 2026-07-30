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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end integration tests for ADR 0027 Phase 4 (loyalty + gift cards) against the REAL
 * Flyway-migrated schema — i.e. against the ACTUAL V37-seeded {@code posting_template}/{@code
 * role_account_map} rows (not mocks), the exact pairing the V37 migration's DEPLOYMENT-ORDER HAZARD
 * banner requires ship together. Proves the migration's reference data and this Java change
 * integrate correctly, end to end, through real {@code @SpringBootTest} services.
 *
 * <p>Covers: net-revenue accumulation with loyalty redemption, a mixed cash+gift-card sale posting
 * against the real V37 SALE v3 template, the legacy-byte-identical regression proven against the
 * REAL (now only-effective) v3 template, and per-leg reversal of a gift-card+loyalty sale including
 * the new GIFT_CARD_LIABILITY/LOYALTY_DISCOUNT legs.
 */
@SpringBootTest
class Phase4LoyaltyGiftCardIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "ee11ee11-ee11-ee11-ee11-ee11ee11ee11";
  private static final UUID BUSINESS = UUID.fromString("ff22ff22-ff22-ff22-ff22-ff22ff22ff22");
  private static final Instant OCCURRED = Instant.parse("2026-08-01T12:00:00Z");
  private static final String PERIOD = "2026-08";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;

  // -----------------------------------------------------------------------
  // Net-revenue math: loyaltyRedeemed is a contra-revenue deduction
  // -----------------------------------------------------------------------

  @Test
  void netRevenueSubtractsLoyaltyRedeemedFromConsolidatedOutletAndPnl() throws Exception {
    // subtotal=30_000, discount=0, loyaltyRedeemed=5_000, SC=0, tax=0 -> amount=25_000.
    // netRevenue = subtotal - discount - loyaltyRedeemed = 25_000.
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        saleEvent(eventId, 30_000L, 0L, 0L, 0L, 25_000L, "CASH", 5_000L, null, null));

    assertThat(consolidatedRevenueMinor())
        .as("consolidated_revenue must accumulate NET (subtotal − loyaltyRedeemed), not gross")
        .isEqualTo(25_000L);
    assertThat(outletRevenueMinor())
        .as("outlet_revenue must accumulate the same extended net")
        .isEqualTo(25_000L);
    assertThat(consolidatedPnlRevenueMinor()).isEqualTo(25_000L);
  }

  @Test
  void giftCardRedemptionDoesNotReduceNetRevenueOnlySplitsTheTender() throws Exception {
    // subtotal=40_000, no discount, no loyalty, gift card covers 15_000 of the tender.
    // netRevenue = subtotal - discount - loyaltyRedeemed = 40_000 (gift card is TENDER, not
    // revenue-affecting).
    UUID eventId = UUID.randomUUID();
    revenuePostingService.handle(
        saleEvent(eventId, 40_000L, 0L, 0L, 0L, 40_000L, "CASH", null, null, 15_000L));

    assertThat(consolidatedRevenueMinor())
        .as("gift-card redemption must NOT reduce net revenue (it is a tender split, not a"
            + " discount)")
        .isEqualTo(40_000L);
  }

  // -----------------------------------------------------------------------
  // Real V37 SALE v3 template: mixed cash + gift-card tender balance
  // -----------------------------------------------------------------------

  @Test
  void mixedCashAndGiftCardTenderSalePostsBalancedEntryAgainstRealV37Template() throws Exception {
    UUID eventId = UUID.randomUUID();
    // amount=60_000, gift card covers 25_000, residual 35_000 on CASH.
    revenuePostingService.handle(
        saleEvent(eventId, 60_000L, 0L, 0L, 0L, 60_000L, "CASH", null, null, 25_000L));

    assertThat(debitAmountFor(eventId, "1900"))
        .as("residual CASH_CLEARING debit = NET_TENDER = 60,000 − 25,000")
        .isEqualTo(35_000L);
    assertThat(debitAmountFor(eventId, "2500"))
        .as("GIFT_CARD_LIABILITY (V37, 2500) debit = GIFT_CARD_TENDER = 25,000")
        .isEqualTo(25_000L);
    assertThat(creditAmountFor(eventId, "4000")).isEqualTo(60_000L);
    assertBalanced(eventId, 60_000L);
  }

  // -----------------------------------------------------------------------
  // Legacy byte-identical regression, proven against the REAL (only-effective) v3 template
  // -----------------------------------------------------------------------

  @Test
  void legacySaleThroughTheRealOnlyEffectiveV3TemplateStillProducesTheV2Shape() throws Exception {
    // A legacy-shaped sale (no Phase 4 fields at all) posted AFTER V37 has landed: the resolver
    // picks v3 unconditionally (highest version) for EVERY SaleRecorded — this is exactly the
    // deployment-order hazard the V37 banner describes. The resulting GL entry must carry ONLY the
    // pre-Phase-4 account codes (no GIFT_CARD_LIABILITY 2500, no LOYALTY_DISCOUNT 4030).
    UUID eventId = UUID.randomUUID();
    // subtotal=30_000, discount=5_000, SC=1_250, tax=2_625, grand=28_875 (Phase 2 worked example).
    revenuePostingService.handle(
        saleEvent(eventId, 30_000L, 5_000L, 1_250L, 2_625L, 28_875L, "CASH", null, null, null));

    Set<String> accountCodes = accountCodesFor(eventId);
    assertThat(accountCodes)
        .as("legacy sale under v3 must NOT touch the new Phase 4 accounts")
        .doesNotContain("2500", "4030");
    assertThat(accountCodes)
        .as("legacy sale under v3 must post the exact pre-Phase-4 account set")
        .containsExactlyInAnyOrder("1900", "4010", "4000", "4020", "2100");

    assertThat(debitAmountFor(eventId, "1900"))
        .as("NET_TENDER collapses to GROSS (no gift card) = grand total")
        .isEqualTo(28_875L);
    assertBalanced(eventId, 33_875L); // Σdr == Σcr (28,875 + 5,000), not the grand total
  }

  // -----------------------------------------------------------------------
  // Reversal per-leg: void of a gift-card + loyalty sale contras every leg
  // -----------------------------------------------------------------------

  @Test
  void voidOfGiftCardAndLoyaltySaleContrasEveryLegAndUnwindsExtendedNetToZero() throws Exception {
    // subtotal=50_000, discount=0, loyaltyRedeemed=5_000, SC=0, tax=0 -> pre-gift-card amount would
    // be 45_000; gift card covers 20_000 of that -> amount=45_000, giftCardRedeemed=20_000.
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(
        saleEventWithSaleId(
            saleEventId, saleId, 50_000L, 0L, 0L, 0L, 45_000L, "CASH", 5_000L, null, 20_000L));

    long netRevenueAfterSale = consolidatedRevenueMinor();
    assertThat(netRevenueAfterSale)
        .as("net = subtotal(50,000) - loyaltyRedeemed(5,000) = 45,000")
        .isEqualTo(45_000L);

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(45_000L, "IDR"),
            OCCURRED,
            "CASH"));

    // The per-leg contra entry mechanism replays EVERY stored line of the original SALE entry
    // (ReversalPostingWriter#buildAndSavePerLegReversalEntryFromLines) — it must therefore
    // automatically contra the NEW GIFT_CARD_LIABILITY and LOYALTY_DISCOUNT legs too, with no
    // code change required there.
    Set<String> voidAccountCodes = accountCodesFor(voidId);
    assertThat(voidAccountCodes)
        .as("void must contra EVERY original leg, including the Phase 4 ones")
        .containsExactlyInAnyOrder("1900", "2500", "4030", "4000");

    // GIFT_CARD_LIABILITY: original was a DEBIT (20,000) -> contra is a CREDIT (20,000).
    assertThat(creditAmountFor(voidId, "2500")).isEqualTo(20_000L);
    // LOYALTY_DISCOUNT: original was a DEBIT (5,000) -> contra is a CREDIT (5,000).
    assertThat(creditAmountFor(voidId, "4030")).isEqualTo(5_000L);
    // GROSS_REVENUE (4000): original was a CREDIT (50,000) -> contra is a DEBIT (50,000).
    assertThat(debitAmountFor(voidId, "4000")).isEqualTo(50_000L);

    assertBalanced(voidId, 50_000L);

    // The read model must unwind by the EXTENDED net (45,000), netting to zero.
    assertThat(consolidatedRevenueMinor())
        .as("void must unwind consolidated_revenue by the extended net (subtotal − loyaltyRedeemed)")
        .isZero();
  }

  // -----------------------------------------------------------------------
  // helpers — event factories
  // -----------------------------------------------------------------------

  @SuppressWarnings("checkstyle:ParameterNumber")
  private SaleRecordedEvent saleEvent(
      UUID eventId,
      long subtotal,
      long discount,
      long sc,
      long tax,
      long grand,
      String tenderType,
      Long loyaltyRedeemedMinor,
      String giftCardId,
      Long giftCardRedeemedMinor) {
    return saleEventWithSaleId(
        eventId,
        UUID.randomUUID(),
        subtotal,
        discount,
        sc,
        tax,
        grand,
        tenderType,
        loyaltyRedeemedMinor,
        giftCardId,
        giftCardRedeemedMinor);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private SaleRecordedEvent saleEventWithSaleId(
      UUID eventId,
      UUID saleId,
      long subtotal,
      long discount,
      long sc,
      long tax,
      long grand,
      String tenderType,
      Long loyaltyRedeemedMinor,
      String giftCardId,
      Long giftCardRedeemedMinor) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        BUSINESS,
        Money.ofMinor(grand, "IDR"),
        OCCURRED,
        tenderType,
        subtotal,
        discount,
        sc,
        tax,
        "v1-illustrative",
        true,
        null,
        null,
        loyaltyRedeemedMinor,
        giftCardId,
        giftCardRedeemedMinor);
  }

  // -----------------------------------------------------------------------
  // helpers — DB reads over admin (BYPASSRLS) connection
  // -----------------------------------------------------------------------

  private long consolidatedRevenueMinor() throws Exception {
    try (Connection admin = adminConnection();
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

  private long outletRevenueMinor() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(revenue_minor), 0) FROM outlet_revenue"
                    + " WHERE company_id = '"
                    + TENANT
                    + "' AND business_id = '"
                    + BUSINESS
                    + "' AND period = '"
                    + PERIOD
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long consolidatedPnlRevenueMinor() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(revenue_minor), 0) FROM consolidated_pnl"
                    + " WHERE company_id = '"
                    + TENANT
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long debitAmountFor(UUID sourceEventId, String accountCode) throws Exception {
    return legAmount(sourceEventId, accountCode, true);
  }

  private long creditAmountFor(UUID sourceEventId, String accountCode) throws Exception {
    return legAmount(sourceEventId, accountCode, false);
  }

  private long legAmount(UUID sourceEventId, String accountCode, boolean debit) throws Exception {
    String column = debit ? "debit_minor" : "credit_minor";
    String sql =
        "SELECT jl."
            + column
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + sourceEventId
            + "' AND jl.account_code = '"
            + accountCode
            + "'";
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next())
          .as("no line found for account " + accountCode + " on sourceEventId=" + sourceEventId)
          .isTrue();
      return rs.getLong(1);
    }
  }

  private Set<String> accountCodesFor(UUID sourceEventId) throws Exception {
    String sql =
        "SELECT jl.account_code"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + sourceEventId
            + "'";
    Set<String> codes = new HashSet<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        codes.add(rs.getString("account_code"));
      }
    }
    return codes;
  }

  private void assertBalanced(UUID sourceEventId, long expectedTotal) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + sourceEventId
            + "'";
    long totalDebit = 0L;
    long totalCredit = 0L;
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        totalDebit += rs.getLong("debit_minor");
        totalCredit += rs.getLong("credit_minor");
      }
    }
    assertThat(totalDebit).as("Σdebit must equal Σcredit").isEqualTo(totalCredit);
    assertThat(totalDebit).as("Σdebit must equal the expected total").isEqualTo(expectedTotal);
  }

  private static Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
