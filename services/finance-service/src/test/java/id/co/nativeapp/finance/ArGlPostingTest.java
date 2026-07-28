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
 * Unit tests for the AR GL postings assembled by {@link
 * JournalPostingService#buildEntryFromBreakdown} (mocked resolvers; no Spring/DB). Verifies the
 * invoice-issue (Dr AR / Cr revenue / Cr output VAT), the payment (Dr cash / Cr AR), the void
 * contra, and the zero-omit of the TAX line for a non-taxable invoice — each entry balanced.
 */
class ArGlPostingTest {

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
    when(roleResolver.resolve(eq(AccountRole.AR), any())).thenReturn("1200");
    when(roleResolver.resolve(eq(AccountRole.REVENUE), any())).thenReturn("4000");
    when(roleResolver.resolve(eq(AccountRole.VAT_OUTPUT), any())).thenReturn("2200");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
  }

  private PostingTemplate issueTemplate() {
    return new PostingTemplate(
        EventKind.INVOICE_ISSUED,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.AR, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.REVENUE, Side.CREDIT, "GROSS_REVENUE"),
            new TemplateLine(3, AccountRole.VAT_OUTPUT, Side.CREDIT, "TAX")));
  }

  private PostingTemplate paymentTemplate() {
    return new PostingTemplate(
        EventKind.PAYMENT_RECEIVED,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.AR, Side.CREDIT, "GROSS")));
  }

  private PostingTemplate voidTemplate() {
    return new PostingTemplate(
        EventKind.INVOICE_VOID,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.REVENUE, Side.DEBIT, "GROSS_REVENUE"),
            new TemplateLine(2, AccountRole.VAT_OUTPUT, Side.DEBIT, "TAX"),
            new TemplateLine(3, AccountRole.AR, Side.CREDIT, "GROSS")));
  }

  private static Map<String, Money> issueAmounts(long net, long tax) {
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", Money.ofMinor(net + tax, "IDR"));
    amounts.put("GROSS_REVENUE", Money.ofMinor(net, "IDR"));
    amounts.put("TAX", Money.ofMinor(tax, "IDR"));
    return amounts;
  }

  @Test
  void taxableInvoiceIssuePostsThreeBalancedLines() {
    when(templateResolver.resolve(eq(EventKind.INVOICE_ISSUED), any())).thenReturn(issueTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.INVOICE_ISSUED,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AR invoice issued",
            true,
            issueAmounts(1_000_000L, 110_000L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_110_000L);
    assertThat(lineFor(lines, "1200").getDebitMinor()).isEqualTo(1_110_000L); // Dr AR = total
    assertThat(lineFor(lines, "4000").getCreditMinor()).isEqualTo(1_000_000L); // Cr revenue = net
    assertThat(lineFor(lines, "2200").getCreditMinor()).isEqualTo(110_000L); // Cr VAT = tax
  }

  @Test
  void nonTaxableInvoiceIssueOmitsTheTaxLine() {
    when(templateResolver.resolve(eq(EventKind.INVOICE_ISSUED), any())).thenReturn(issueTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.INVOICE_ISSUED,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AR invoice issued",
            false,
            issueAmounts(1_000_000L, 0L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2); // TAX line zero-omitted
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lines).noneMatch(l -> l.getAccountCode().equals("2200"));
  }

  @Test
  void paymentPostsDrCashCrAr() {
    when(templateResolver.resolve(eq(EventKind.PAYMENT_RECEIVED), any()))
        .thenReturn(paymentTemplate());
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", Money.ofMinor(500_000L, "IDR"));

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.PAYMENT_RECEIVED,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AR payment received",
            false,
            amounts);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lineFor(lines, "1900").getDebitMinor()).isEqualTo(500_000L);
    assertThat(lineFor(lines, "1200").getCreditMinor()).isEqualTo(500_000L);
  }

  @Test
  void voidIsTheContraOfIssue() {
    when(templateResolver.resolve(eq(EventKind.INVOICE_VOID), any())).thenReturn(voidTemplate());

    JournalEntry entry =
        service.buildEntryFromBreakdown(
            EventKind.INVOICE_VOID,
            PERIOD,
            NOW,
            UUID.randomUUID(),
            "AR invoice voided",
            true,
            issueAmounts(1_000_000L, 110_000L));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_110_000L);
    assertThat(lineFor(lines, "1200").getCreditMinor()).isEqualTo(1_110_000L); // Cr AR = total
    assertThat(lineFor(lines, "4000").getDebitMinor()).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "2200").getDebitMinor()).isEqualTo(110_000L);
  }

  @Test
  void missingGrossThrows() {
    assertThatThrownBy(
            () ->
                service.buildEntryFromBreakdown(
                    EventKind.PAYMENT_RECEIVED,
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
