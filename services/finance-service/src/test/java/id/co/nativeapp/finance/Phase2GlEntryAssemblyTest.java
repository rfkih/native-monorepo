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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JournalPostingService#buildEntryForSale} — the Phase 2 breakdown-aware GL
 * entry builder (B2 coverage from the money review).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>A sale WITH discount + service charge + tax produces a <strong>balanced</strong> 5-line
 *       entry (Σdr == Σcr).
 *   <li>Zero-line omission: discount=0 → no SALES_DISCOUNT leg; tax=0 → no TAX_PAYABLE leg.
 *   <li>Amounts are SELECTED from the event breakdown fields (not recomputed by finance).
 *   <li>The {@code usesIllustrativeRules} flag is OR-propagated (template OR event).
 *   <li>Backward-compat: a legacy null-breakdown {@code SaleRecordedEvent} falls back to the 2-line
 *       GROSS entry (net == gross).
 * </ul>
 *
 * <p>Pure-unit (no Spring, no DB): mocked resolvers and a {@link SaleRecordedEvent} constructed
 * from known breakdown constants.
 */
class Phase2GlEntryAssemblyTest {

  private static final Instant NOW = Instant.parse("2026-06-14T08:30:00Z");
  private static final String PERIOD = "2026-06";
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID SALE_ID = UUID.randomUUID();

  // Phase 2 breakdown (IDR, illustrative illustrative): subtotal=30k, discount=0, SC=1.5k,
  // tax=3.15k
  // grandTotal = 30_000 + 1_500 + 3_150 = 34_650
  private static final long SUBTOTAL = 30_000L;
  private static final long DISCOUNT = 0L;
  private static final long SC = 1_500L;
  private static final long TAX = 3_150L;
  private static final long GRAND = 34_650L;

  private PostingTemplateResolver templateResolver;
  private RoleAccountResolver roleResolver;
  private JournalPostingService service;

  @BeforeEach
  void setUp() {
    templateResolver = mock(PostingTemplateResolver.class);
    roleResolver = mock(RoleAccountResolver.class);
    service = new JournalPostingService(templateResolver, roleResolver);
  }

  /**
   * Builds the Phase 2 SALE posting template (5 lines): Dr CLEARING gross, Cr GROSS_REVENUE
   * subtotal, Dr SALES_DISCOUNT discount, Cr SC_REVENUE service_charge, Cr TAX_PAYABLE tax.
   */
  private PostingTemplate phase2SaleTemplate(boolean illustrative) {
    return new PostingTemplate(
        EventKind.SALE,
        2,
        illustrative,
        List.of(
            new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.GROSS_REVENUE, Side.CREDIT, "GROSS_REVENUE"),
            new TemplateLine(3, AccountRole.SALES_DISCOUNT, Side.DEBIT, "DISCOUNT"),
            new TemplateLine(4, AccountRole.SERVICE_CHARGE_REVENUE, Side.CREDIT, "SERVICE_CHARGE"),
            new TemplateLine(5, AccountRole.TAX_PAYABLE, Side.CREDIT, "TAX")));
  }

  private void wireResolvers() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(phase2SaleTemplate(true));
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.GROSS_REVENUE), any())).thenReturn("4000");
    when(roleResolver.resolve(eq(AccountRole.SALES_DISCOUNT), any())).thenReturn("4010");
    when(roleResolver.resolve(eq(AccountRole.SERVICE_CHARGE_REVENUE), any())).thenReturn("4020");
    when(roleResolver.resolve(eq(AccountRole.TAX_PAYABLE), any())).thenReturn("2100");
  }

  private SaleRecordedEvent phase2Event(
      long subtotal, long discount, long sc, long tax, long grand) {
    return new SaleRecordedEvent(
        EVENT_ID,
        SALE_ID,
        "tenant-01",
        UUID.randomUUID(),
        Money.ofMinor(grand, "IDR"),
        NOW,
        "CASH",
        subtotal,
        discount,
        sc,
        tax,
        "v1-illustrative",
        true);
  }

  // -----------------------------------------------------------------------
  // 1. Balanced 5-line entry (discount=0 so SALES_DISCOUNT line is omitted → 4 lines)
  // -----------------------------------------------------------------------

  /**
   * Sale with discount=0 produces a 4-line balanced entry: Dr CLEARING 34,650 | Cr GROSS_REVENUE
   * 30,000 | Cr SC_REVENUE 1,500 | Cr TAX_PAYABLE 3,150. SALES_DISCOUNT is omitted (amount==0).
   */
  @Test
  void zeroDiscountSaleProducesBalancedFourLineEntry() {
    wireResolvers();
    SaleRecordedEvent event = phase2Event(SUBTOTAL, 0L, SC, TAX, GRAND);

    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", event, null);

    List<JournalLine> lines = entry.getLines();
    // discount=0 → SALES_DISCOUNT line omitted; 5 template lines − 1 zero = 4 lines.
    assertThat(lines).as("discount=0 must omit SALES_DISCOUNT leg").hasSize(4);

    long totalDebit = lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).as("Σdebit must equal Σcredit (balanced)").isEqualTo(totalCredit);
    assertThat(totalDebit).as("Σdebit must equal grand total").isEqualTo(GRAND);
  }

  /**
   * Amounts are SELECTED from event breakdown fields (not recomputed): the CLEARING debit must
   * exactly equal the grand total carried on the event; the GROSS_REVENUE credit must equal the
   * subtotal; and so on. Finance never recomputes.
   */
  @Test
  void breakdownAmountsAreSelectedFromEvent() {
    wireResolvers();
    SaleRecordedEvent event = phase2Event(SUBTOTAL, 0L, SC, TAX, GRAND);
    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", event, null);

    JournalLine clearing =
        entry.getLines().stream()
            .filter(l -> "1900".equals(l.getAccountCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No CLEARING (1900) line"));
    assertThat(clearing.getDebitMinor())
        .as("CLEARING debit must equal grand total (not recomputed)")
        .isEqualTo(GRAND);

    JournalLine revenue =
        entry.getLines().stream()
            .filter(l -> "4000".equals(l.getAccountCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No GROSS_REVENUE (4000) line"));
    assertThat(revenue.getCreditMinor())
        .as("GROSS_REVENUE credit must equal subtotal from event")
        .isEqualTo(SUBTOTAL);

    JournalLine sc =
        entry.getLines().stream()
            .filter(l -> "4020".equals(l.getAccountCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No SC_REVENUE (4020) line"));
    assertThat(sc.getCreditMinor())
        .as("SC_REVENUE credit must equal service_charge from event")
        .isEqualTo(SC);

    JournalLine tax =
        entry.getLines().stream()
            .filter(l -> "2100".equals(l.getAccountCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No TAX_PAYABLE (2100) line"));
    assertThat(tax.getCreditMinor())
        .as("TAX_PAYABLE credit must equal tax from event")
        .isEqualTo(TAX);
  }

  /**
   * Sale WITH non-zero discount produces a 5-line balanced entry. The SALES_DISCOUNT debit is
   * present and Σdebit == Σcredit.
   *
   * <p>Worked example (IDR): subtotal=30k, discount=5k, SC=1.25k (5% of 25k), tax=2.625k (10% of
   * 26.25k), grand=28.875k. CLEARING Dr 28,875 | GROSS_REVENUE Cr 30,000 | SALES_DISCOUNT Dr 5,000
   * | SC_REVENUE Cr 1,250 | TAX_PAYABLE Cr 2,625. Σdr=33,875; Σcr=33,875. ✓
   */
  @Test
  void nonZeroDiscountSaleProducesBalancedFiveLineEntry() {
    wireResolvers();
    // subtotal=30k, discount=5k, SC=1.25k, tax=2.625k, grand=28.875k
    long sub = 30_000L;
    long disc = 5_000L;
    long scAmt = 1_250L;
    long taxAmt = 2_625L;
    long grandAmt = 28_875L; // 25_000 + 1_250 + 2_625

    SaleRecordedEvent event = phase2Event(sub, disc, scAmt, taxAmt, grandAmt);
    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", event, null);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).as("discount > 0 must include SALES_DISCOUNT leg (5 lines)").hasSize(5);

    long totalDebit = lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit)
        .as("Σdebit must equal Σcredit (balanced, S1 discount case)")
        .isEqualTo(totalCredit);

    // SALES_DISCOUNT debit must equal the discount amount from the event.
    JournalLine discountLine =
        entry.getLines().stream()
            .filter(l -> "4010".equals(l.getAccountCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No SALES_DISCOUNT (4010) line"));
    assertThat(discountLine.getDebitMinor())
        .as("SALES_DISCOUNT debit must equal discount from event")
        .isEqualTo(disc);
  }

  /** Zero tax → TAX_PAYABLE leg omitted. Entry is still balanced. */
  @Test
  void zeroTaxSaleOmitsTaxPayableLeg() {
    wireResolvers();
    // tax=0: CLEARING Dr 31,500 | GROSS_REVENUE Cr 30,000 | SC_REVENUE Cr 1,500.
    long grandNoTax = SUBTOTAL + SC; // 31,500
    SaleRecordedEvent event = phase2Event(SUBTOTAL, 0L, SC, 0L, grandNoTax);

    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", event, null);

    assertThat(entry.getLines())
        .as("tax=0 must omit TAX_PAYABLE leg")
        .noneMatch(l -> "2100".equals(l.getAccountCode()));

    long totalDebit = entry.getLines().stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = entry.getLines().stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).as("entry must be balanced even with no tax leg").isEqualTo(totalCredit);
  }

  // -----------------------------------------------------------------------
  // 2. Illustrative flag propagation
  // -----------------------------------------------------------------------

  @Test
  void illustrativeFlagOrPropagatedFromEventOntoEntry() {
    // Template uses_illustrative=false; event says true → entry must be illustrative.
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(phase2SaleTemplate(false));
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.GROSS_REVENUE), any())).thenReturn("4000");
    when(roleResolver.resolve(eq(AccountRole.SALES_DISCOUNT), any())).thenReturn("4010");
    when(roleResolver.resolve(eq(AccountRole.SERVICE_CHARGE_REVENUE), any())).thenReturn("4020");
    when(roleResolver.resolve(eq(AccountRole.TAX_PAYABLE), any())).thenReturn("2100");

    SaleRecordedEvent event = phase2Event(SUBTOTAL, 0L, SC, TAX, GRAND);
    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", event, null);

    assertThat(entry.isUsesIllustrativeRules())
        .as("illustrative flag must be OR'd from event even when template is non-illustrative")
        .isTrue();
  }

  // -----------------------------------------------------------------------
  // 3. Backward-compat: legacy null-breakdown falls back to 2-line GROSS entry
  // -----------------------------------------------------------------------

  /**
   * A legacy/carwash {@code SaleRecordedEvent} (null breakdown fields) must fall back to the 2-line
   * GROSS entry (Dr CLEARING gross / Cr REVENUE gross) via the 7-arg {@code buildEntry} path.
   * Finance calls {@code buildEntryForSale} which handles breakdown-aware; for a null breakdown the
   * event's {@code effectiveSubtotal()} == grand total and all other fields == 0, so only the GROSS
   * line is non-zero — but the template itself controls which lines are emitted. Here we use a
   * 2-line GROSS template (the Phase 1 seed) to simulate the legacy path.
   */
  @Test
  void legacyNullBreakdownFallsBackToTwoLineGrossEntry() {
    // Use a legacy 2-line GROSS template (no Phase 2 basis values).
    PostingTemplate legacyTemplate =
        new PostingTemplate(
            EventKind.SALE,
            1,
            false, // real rules (non-illustrative, from carwash)
            List.of(
                new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "GROSS"),
                new TemplateLine(2, AccountRole.REVENUE, Side.CREDIT, "GROSS")));

    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(legacyTemplate);
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.REVENUE), any())).thenReturn("4000");

    // Legacy event: null breakdown (carwash / pre-Phase-2 producer).
    SaleRecordedEvent legacyEvent =
        new SaleRecordedEvent(
            EVENT_ID, "tenant-legacy", UUID.randomUUID(), Money.ofMinor(20_000L, "IDR"), NOW);

    JournalEntry entry =
        service.buildEntryForSale(
            EventKind.SALE, PERIOD, NOW, EVENT_ID, "SaleRecorded", legacyEvent, null);

    assertThat(entry.getLines()).hasSize(2);
    long totalDebit = entry.getLines().stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = entry.getLines().stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(20_000L);
    // Legacy event: uses_illustrative=false (carwash), template non-illustrative → false.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }
}
