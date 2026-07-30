package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.domain.PostingTemplate;
import id.co.nativeapp.finance.gl.domain.TemplateLine;
import id.co.nativeapp.finance.gl.domain.TemplateLine.Side;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.gl.service.PostingTemplateResolver;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests (no Spring, no DB) for {@link JournalPostingService#buildEntryForSale} against
 * the Phase 4 (ADR 0027) SALE v3 posting template — the exact 7-line shape V37 seeds:
 *
 * <pre>
 * 1. Dr CASH_CLEARING       NET_TENDER        (amount − gift_card_redeemed)
 * 2. Dr GIFT_CARD_LIABILITY GIFT_CARD_TENDER  (gift_card_redeemed)
 * 3. Dr SALES_DISCOUNT      DISCOUNT
 * 4. Dr LOYALTY_DISCOUNT    LOYALTY_REDEEMED
 * 5. Cr GROSS_REVENUE       GROSS_REVENUE
 * 6. Cr SERVICE_CHARGE_REVENUE SERVICE_CHARGE
 * 7. Cr TAX_PAYABLE         TAX
 * </pre>
 *
 * <p>Covers the balance + clearing-split posting (mixed cash/gift-card tender; fully-gift-card-paid
 * tender omits the clearing leg; loyalty adds a contra-revenue leg) and — the regression-critical
 * proof — that a LEGACY (pre-Phase-4) sale posted under v3 produces the exact same set of (account,
 * side, amount) lines as the OLD v2 template would have produced for the identical event (ADR 0027
 * decision 5 / the V37 migration header's "byte-identical" claim).
 */
class Phase4SaleGlEntryAssemblyTest {

  private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
  private static final String PERIOD = "2026-08";
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID SALE_ID = UUID.randomUUID();

  private static final String CASH_CLEARING_CODE = "1900";
  private static final String GIFT_CARD_LIABILITY_CODE = "2500";
  private static final String SALES_DISCOUNT_CODE = "4010";
  private static final String LOYALTY_DISCOUNT_CODE = "4030";
  private static final String GROSS_REVENUE_CODE = "4000";
  private static final String SERVICE_CHARGE_REVENUE_CODE = "4020";
  private static final String TAX_PAYABLE_CODE = "2100";

  private PostingTemplateResolver templateResolver;
  private RoleAccountResolver roleResolver;
  private JournalPostingService service;

  @BeforeEach
  void setUp() {
    templateResolver = mock(PostingTemplateResolver.class);
    roleResolver = mock(RoleAccountResolver.class);
    service = new JournalPostingService(templateResolver, roleResolver);

    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn(CASH_CLEARING_CODE);
    when(roleResolver.resolve(eq(AccountRole.GIFT_CARD_LIABILITY), any()))
        .thenReturn(GIFT_CARD_LIABILITY_CODE);
    when(roleResolver.resolve(eq(AccountRole.SALES_DISCOUNT), any()))
        .thenReturn(SALES_DISCOUNT_CODE);
    when(roleResolver.resolve(eq(AccountRole.LOYALTY_DISCOUNT), any()))
        .thenReturn(LOYALTY_DISCOUNT_CODE);
    when(roleResolver.resolve(eq(AccountRole.GROSS_REVENUE), any())).thenReturn(GROSS_REVENUE_CODE);
    when(roleResolver.resolve(eq(AccountRole.SERVICE_CHARGE_REVENUE), any()))
        .thenReturn(SERVICE_CHARGE_REVENUE_CODE);
    when(roleResolver.resolve(eq(AccountRole.TAX_PAYABLE), any())).thenReturn(TAX_PAYABLE_CODE);
  }

  /** The exact V37 SALE v3 template (7 lines, same line_no/role/side/basis as the migration). */
  private static PostingTemplate saleV3Template() {
    return new PostingTemplate(
        EventKind.SALE,
        3,
        true,
        List.of(
            new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "NET_TENDER"),
            new TemplateLine(2, AccountRole.GIFT_CARD_LIABILITY, Side.DEBIT, "GIFT_CARD_TENDER"),
            new TemplateLine(3, AccountRole.SALES_DISCOUNT, Side.DEBIT, "DISCOUNT"),
            new TemplateLine(4, AccountRole.LOYALTY_DISCOUNT, Side.DEBIT, "LOYALTY_REDEEMED"),
            new TemplateLine(5, AccountRole.GROSS_REVENUE, Side.CREDIT, "GROSS_REVENUE"),
            new TemplateLine(6, AccountRole.SERVICE_CHARGE_REVENUE, Side.CREDIT, "SERVICE_CHARGE"),
            new TemplateLine(7, AccountRole.TAX_PAYABLE, Side.CREDIT, "TAX")));
  }

  /** The exact V17 SALE v2 template (5 lines) — the pre-Phase-4 shape, for the regression proof. */
  private static PostingTemplate saleV2Template() {
    return new PostingTemplate(
        EventKind.SALE,
        2,
        true,
        List.of(
            new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.SALES_DISCOUNT, Side.DEBIT, "DISCOUNT"),
            new TemplateLine(3, AccountRole.GROSS_REVENUE, Side.CREDIT, "GROSS_REVENUE"),
            new TemplateLine(4, AccountRole.SERVICE_CHARGE_REVENUE, Side.CREDIT, "SERVICE_CHARGE"),
            new TemplateLine(5, AccountRole.TAX_PAYABLE, Side.CREDIT, "TAX")));
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static SaleRecordedEvent event(
      long subtotal,
      long discount,
      long sc,
      long tax,
      long grand,
      String tenderType,
      Long loyaltyRedeemedMinor,
      Long giftCardRedeemedMinor) {
    return new SaleRecordedEvent(
        EVENT_ID,
        SALE_ID,
        "tenant-phase4",
        UUID.randomUUID(),
        Money.ofMinor(grand, "IDR"),
        NOW,
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
        null,
        giftCardRedeemedMinor);
  }

  // -----------------------------------------------------------------------
  // Mixed cash + gift-card tender
  // -----------------------------------------------------------------------

  @Test
  void mixedCashAndGiftCardTenderProducesBalancedEntryWithBothClearingAndLiabilityLegs() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleV3Template());

    // amount=50_000, gift-card covers 20_000 of it, no discount/SC/tax/loyalty.
    SaleRecordedEvent evt = event(50_000L, 0L, 0L, 0L, 50_000L, "CASH", null, 20_000L);

    JournalEntry entry =
        service.buildEntryForSale(EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", evt, null);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines)
        .as("CLEARING (residual) + GIFT_CARD_LIABILITY + GROSS_REVENUE — 3 non-zero lines")
        .hasSize(3);

    JournalLine clearing = lineFor(lines, CASH_CLEARING_CODE);
    assertThat(clearing.getDebitMinor()).as("NET_TENDER = 50,000 − 20,000").isEqualTo(30_000L);

    JournalLine liability = lineFor(lines, GIFT_CARD_LIABILITY_CODE);
    assertThat(liability.getDebitMinor()).as("GIFT_CARD_TENDER = 20,000").isEqualTo(20_000L);

    JournalLine revenue = lineFor(lines, GROSS_REVENUE_CODE);
    assertThat(revenue.getCreditMinor()).isEqualTo(50_000L);

    assertBalanced(lines, 50_000L);
  }

  @Test
  void fullyGiftCardPaidWithNullTenderOmitsClearingLegAndLiabilityCarriesFullDebit() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleV3Template());

    // amount == gift_card_redeemed (fully paid by gift card); tender_type null (no residual
    // tender).
    SaleRecordedEvent evt = event(20_000L, 0L, 0L, 0L, 20_000L, null, null, 20_000L);

    JournalEntry entry =
        service.buildEntryForSale(EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", evt, null);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines)
        .as("NET_TENDER == 0 → CLEARING line omitted; only GIFT_CARD_LIABILITY + GROSS_REVENUE")
        .hasSize(2)
        .noneMatch(l -> CASH_CLEARING_CODE.equals(l.getAccountCode()));

    JournalLine liability = lineFor(lines, GIFT_CARD_LIABILITY_CODE);
    assertThat(liability.getDebitMinor())
        .as("GIFT_CARD_LIABILITY carries the FULL debit when fully gift-card-paid")
        .isEqualTo(20_000L);

    assertBalanced(lines, 20_000L);
  }

  @Test
  void loyaltyRedemptionAddsContraRevenueLegAndStaysBalanced() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleV3Template());

    // subtotal=30_000, loyaltyRedeemed=5_000, no discount/SC/tax/giftcard -> amount=25_000.
    SaleRecordedEvent evt = event(30_000L, 0L, 0L, 0L, 25_000L, "CASH", 5_000L, null);

    JournalEntry entry =
        service.buildEntryForSale(EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", evt, null);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines)
        .as("CLEARING + LOYALTY_DISCOUNT + GROSS_REVENUE — GIFT_CARD_LIABILITY omitted (0)")
        .hasSize(3)
        .noneMatch(l -> GIFT_CARD_LIABILITY_CODE.equals(l.getAccountCode()));

    JournalLine clearing = lineFor(lines, CASH_CLEARING_CODE);
    assertThat(clearing.getDebitMinor())
        .as("NET_TENDER = amount (no gift card)")
        .isEqualTo(25_000L);

    JournalLine loyalty = lineFor(lines, LOYALTY_DISCOUNT_CODE);
    assertThat(loyalty.getDebitMinor()).isEqualTo(5_000L);

    JournalLine revenue = lineFor(lines, GROSS_REVENUE_CODE);
    assertThat(revenue.getCreditMinor()).isEqualTo(30_000L);

    assertBalanced(lines, 30_000L);
  }

  // -----------------------------------------------------------------------
  // REGRESSION-CRITICAL: legacy sale posted under v3 == byte-identical to v2
  // -----------------------------------------------------------------------

  /**
   * The named regression test the task requires: a pre-Phase-4 (legacy) sale — no gift card, no
   * loyalty redemption — posted under the NEW v3 template must produce the EXACT SAME set of
   * (account_code, side, amount) lines as the OLD v2 template produced for the identical event.
   * Line numbering may differ (v3 reserves line_no 2 and 4 for the zero-omitted gift-card/loyalty
   * legs), but the resulting journal shape — what actually posts to the ledger — is identical.
   */
  @Test
  void legacySaleEventProducesByteIdenticalEntryUnderV3AsUnderV2() {
    // subtotal=30_000, discount=5_000, SC=1_250, tax=2_625, grand=28_875 — the Phase2
    // non-zero-discount worked example, no Phase 4 fields set (both null).
    SaleRecordedEvent legacyEvent =
        event(30_000L, 5_000L, 1_250L, 2_625L, 28_875L, "CASH", null, null);

    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleV2Template());
    JournalEntry v2Entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", legacyEvent, null);

    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleV3Template());
    JournalEntry v3Entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", legacyEvent, null);

    assertThat(signature(v3Entry.getLines()))
        .as("v3's resulting lines for a legacy event must be BYTE-IDENTICAL to v2's")
        .isEqualTo(signature(v2Entry.getLines()));

    // Both balanced at the same total.
    assertBalanced(v2Entry.getLines(), 33_875L); // 28_875 + 5_000 (Σdr == Σcr, not the grand total)
    assertBalanced(v3Entry.getLines(), 33_875L);
  }

  // -----------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------

  private static JournalLine lineFor(List<JournalLine> lines, String accountCode) {
    return lines.stream()
        .filter(l -> accountCode.equals(l.getAccountCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No line for account " + accountCode));
  }

  private static void assertBalanced(List<JournalLine> lines, long expectedTotal) {
    long totalDebit = lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).as("Σdebit must equal Σcredit").isEqualTo(totalCredit);
    assertThat(totalDebit).as("Σdebit must equal the expected total").isEqualTo(expectedTotal);
  }

  /**
   * A comparable, line_no-independent signature of a journal entry's lines: sorted {@code
   * "accountCode|debit|credit"} tuples, so two entries with the same economic shape but different
   * internal line numbering compare equal.
   */
  private static List<String> signature(List<JournalLine> lines) {
    return lines.stream()
        .map(l -> l.getAccountCode() + "|" + l.getDebitMinor() + "|" + l.getCreditMinor())
        .sorted(Comparator.naturalOrder())
        .toList();
  }
}
