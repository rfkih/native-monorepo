package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the AP GL postings assembled by {@link
 * JournalPostingService#buildEntryFromBreakdown} (mocked resolvers; no Spring/DB) — the mirror of
 * {@link ArGlPostingTest}. Verifies the bill-post (Dr expense / Dr input VAT / Cr AP — the CONTRA
 * of AR's issue), the payment (Dr AP / Cr cash-clearing — the CONTRA of AR's payment), the void
 * contra, and the zero-omit of the TAX line for a non-taxable bill — each entry balanced.
 */
class ApGlPostingTest {

  private static final Instant NOW = Instant.parse("2026-06-14T08:30:00Z");
  private static final String PERIOD = "2026-06";

  private PostingTemplateResolver templateResolver;
  private RoleAccountResolver roleResolver;
  private JournalPostingService service;

  @BeforeEach
  void setUp() {
    templateResolver = mock(PostingTemplateResolver.class);
    roleResolver = mock(RoleAccountResolver.class);
    service = new JournalPostingService(templateResolver, roleResolver);
    when(roleResolver.resolve(eq(AccountRole.AP), any())).thenReturn("2000");
    when(roleResolver.resolve(eq(AccountRole.EXPENSE), any())).thenReturn("5000");
    when(roleResolver.resolve(eq(AccountRole.VAT_INPUT), any())).thenReturn("1300");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
  }

  private PostingTemplate postTemplate() {
    return new PostingTemplate(
        EventKind.BILL_POSTED,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.EXPENSE, Side.DEBIT, "NET"),
            new TemplateLine(2, AccountRole.VAT_INPUT, Side.DEBIT, "TAX"),
            new TemplateLine(3, AccountRole.AP, Side.CREDIT, "GROSS")));
  }

  private PostingTemplate paymentTemplate() {
    return new PostingTemplate(
        EventKind.BILL_PAYMENT_MADE,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.AP, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.CASH_CLEARING, Side.CREDIT, "GROSS")));
  }

  private PostingTemplate voidTemplate() {
    return new PostingTemplate(
        EventKind.BILL_VOID,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.AP, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.EXPENSE, Side.CREDIT, "NET"),
            new TemplateLine(3, AccountRole.VAT_INPUT, Side.CREDIT, "TAX")));
  }

  private static Map<String, Money> postAmounts(long net, long tax) {
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", Money.ofMinor(net + tax, "IDR"));
    amounts.put("NET", Money.ofMinor(net, "IDR"));
    amounts.put("TAX", Money.ofMinor(tax, "IDR"));
    return amounts;
  }

  @Test
  void taxableBillPostPostsThreeBalancedLines() {
    when(templateResolver.resolve(eq(EventKind.BILL_POSTED), any())).thenReturn(postTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.BILL_POSTED,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AP bill posted",
            true,
            postAmounts(1_000_000L, 110_000L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_110_000L);
    assertThat(lineFor(lines, "5000").getDebitMinor()).isEqualTo(1_000_000L); // Dr expense = net
    assertThat(lineFor(lines, "1300").getDebitMinor()).isEqualTo(110_000L); // Dr VAT input = tax
    assertThat(lineFor(lines, "2000").getCreditMinor()).isEqualTo(1_110_000L); // Cr AP = total
  }

  @Test
  void nonTaxableBillPostOmitsTheTaxLine() {
    when(templateResolver.resolve(eq(EventKind.BILL_POSTED), any())).thenReturn(postTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.BILL_POSTED,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AP bill posted",
            false,
            postAmounts(1_000_000L, 0L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2); // TAX line zero-omitted
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lines).noneMatch(l -> l.getAccountCode().equals("1300"));
  }

  @Test
  void paymentPostsDrApCrCashClearing() {
    when(templateResolver.resolve(eq(EventKind.BILL_PAYMENT_MADE), any()))
        .thenReturn(paymentTemplate());
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", Money.ofMinor(500_000L, "IDR"));

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.BILL_PAYMENT_MADE,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AP payment made",
            false,
            amounts);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lineFor(lines, "2000").getDebitMinor()).isEqualTo(500_000L);
    assertThat(lineFor(lines, "1900").getCreditMinor()).isEqualTo(500_000L);
  }

  @Test
  void voidIsTheContraOfPost() {
    when(templateResolver.resolve(eq(EventKind.BILL_VOID), any())).thenReturn(voidTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.BILL_VOID,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AP bill voided",
            true,
            postAmounts(1_000_000L, 110_000L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_110_000L);
    assertThat(lineFor(lines, "2000").getDebitMinor()).isEqualTo(1_110_000L); // Dr AP = total
    assertThat(lineFor(lines, "5000").getCreditMinor()).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "1300").getCreditMinor()).isEqualTo(110_000L);
  }

  @Test
  void missingGrossThrows() {
    assertThatThrownBy(
            () ->
                service.buildEntryFromBreakdown(
                    EventKind.BILL_PAYMENT_MADE,
                    PERIOD,
                    NOW,
                    UUID.randomUUID(),
                    "x",
                    false,
                    new LinkedHashMap<>()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static long totalDebit(List<JournalLine> lines) {
    return lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
  }

  private static long totalCredit(List<JournalLine> lines) {
    return lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
  }

  private static JournalLine lineFor(List<JournalLine> lines, String accountCode) {
    return lines.stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .orElseThrow();
  }
}
