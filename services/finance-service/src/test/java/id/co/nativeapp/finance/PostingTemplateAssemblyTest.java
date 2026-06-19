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
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JournalPostingService} — verifies the template assembly logic using mocked
 * resolvers (no Spring, no DB). Each event kind must produce a balanced two-line entry with the
 * illustrative flag {@code true}, and a missing template must produce a suspense entry (still
 * balanced) also flagged illustrative.
 */
class PostingTemplateAssemblyTest {

  private static final Instant NOW = Instant.parse("2026-06-14T08:30:00Z");
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final Money GROSS_IDR = Money.ofMinor(1_500_000L, "IDR");

  private PostingTemplateResolver templateResolver;
  private RoleAccountResolver roleResolver;
  private JournalPostingService service;

  @BeforeEach
  void setUp() {
    templateResolver = mock(PostingTemplateResolver.class);
    roleResolver = mock(RoleAccountResolver.class);
    service = new JournalPostingService(templateResolver, roleResolver);
  }

  private PostingTemplate saleTemplate() {
    return new PostingTemplate(
        EventKind.SALE,
        1,
        true,
        List.of(
            new TemplateLine(1, AccountRole.CASH_CLEARING, Side.DEBIT, "GROSS"),
            new TemplateLine(2, AccountRole.REVENUE, Side.CREDIT, "GROSS")));
  }

  @Test
  void saleEventProducesBalancedTwoLineEntryFlaggedIllustrative() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleTemplate());
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.REVENUE), any())).thenReturn("4000");

    JournalEntry entry =
        service.buildEntry(EventKind.SALE, GROSS_IDR, NOW, EVENT_ID, "SaleRecorded");

    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(entry.getCurrency()).isEqualTo("IDR");
    assertThat(entry.getSourceEventId()).isEqualTo(EVENT_ID);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);

    long totalDebit = lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(1_500_000L);

    JournalLine debitLine =
        lines.stream().filter(l -> l.getDebitMinor() > 0).findFirst().orElseThrow();
    assertThat(debitLine.getAccountCode()).isEqualTo("1900");

    JournalLine creditLine =
        lines.stream().filter(l -> l.getCreditMinor() > 0).findFirst().orElseThrow();
    assertThat(creditLine.getAccountCode()).isEqualTo("4000");
  }

  @Test
  void expenseEventProducesBalancedEntry() {
    PostingTemplate expenseTemplate =
        new PostingTemplate(
            EventKind.EXPENSE,
            1,
            true,
            List.of(
                new TemplateLine(1, AccountRole.EXPENSE, Side.DEBIT, "GROSS"),
                new TemplateLine(2, AccountRole.CASH_CLEARING, Side.CREDIT, "GROSS")));

    when(templateResolver.resolve(eq(EventKind.EXPENSE), any())).thenReturn(expenseTemplate);
    when(roleResolver.resolve(eq(AccountRole.EXPENSE), any())).thenReturn("5000");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");

    JournalEntry entry =
        service.buildEntry(EventKind.EXPENSE, GROSS_IDR, NOW, EVENT_ID, "ExpenseRecorded");

    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    long totalDebit = entry.getLines().stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = entry.getLines().stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isPositive();
  }

  @Test
  void laborEventProducesBalancedEntry() {
    PostingTemplate laborTemplate =
        new PostingTemplate(
            EventKind.LABOR,
            1,
            true,
            List.of(
                new TemplateLine(1, AccountRole.LABOR_EXPENSE, Side.DEBIT, "GROSS"),
                new TemplateLine(2, AccountRole.LABOR_CLEARING, Side.CREDIT, "GROSS")));

    when(templateResolver.resolve(eq(EventKind.LABOR), any())).thenReturn(laborTemplate);
    when(roleResolver.resolve(eq(AccountRole.LABOR_EXPENSE), any())).thenReturn("6000");
    when(roleResolver.resolve(eq(AccountRole.LABOR_CLEARING), any())).thenReturn("6900");

    JournalEntry entry =
        service.buildEntry(EventKind.LABOR, GROSS_IDR, NOW, EVENT_ID, "LaborCostAllocated");

    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(entry.totalDebit()).isEqualTo(GROSS_IDR);
  }

  @Test
  void missingTemplateProducesBalancedSuspenseEntryFlaggedIllustrative() {
    when(templateResolver.resolve(any(), any())).thenReturn(null);

    JournalEntry entry =
        service.buildEntry(EventKind.SALE, GROSS_IDR, NOW, EVENT_ID, "SaleRecorded");

    long totalDebit = entry.getLines().stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = entry.getLines().stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(1_500_000L);
    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(entry.getLines())
        .allMatch(l -> l.getAccountCode().equals(RoleAccountResolver.SUSPENSE_ACCOUNT_CODE));
  }

  @Test
  void missingRoleMappingFallsBackToSuspenseAccountButRemainsBalanced() {
    when(templateResolver.resolve(eq(EventKind.SALE), any())).thenReturn(saleTemplate());
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.REVENUE), any())).thenReturn(null);

    JournalEntry entry =
        service.buildEntry(EventKind.SALE, GROSS_IDR, NOW, EVENT_ID, "SaleRecorded");

    long totalDebit = entry.getLines().stream().mapToLong(JournalLine::getDebitMinor).sum();
    long totalCredit = entry.getLines().stream().mapToLong(JournalLine::getCreditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isPositive();
  }
}
