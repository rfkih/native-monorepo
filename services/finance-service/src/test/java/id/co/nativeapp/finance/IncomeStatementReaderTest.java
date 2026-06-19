package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit proofs for {@link IncomeStatementReader} — no Spring, no Testcontainers. The {@link
 * GlTrialBalanceReader} is mocked so the GL trial balance can be shaped directly.
 *
 * <p>Verifies: (a) revenue net = credit − debit; (b) expense net = debit − credit; (c) net profit =
 * totalRevenue − totalExpense; (d) ASSET / LIABILITY / EQUITY lines are excluded from the income
 * statement; (e) empty period → Optional.empty(); (f) usesIllustrativeRules OR-ed across lines.
 */
@ExtendWith(MockitoExtension.class)
class IncomeStatementReaderTest {

  private static final String PERIOD = "2026-06";

  @Mock private GlTrialBalanceReader glTrialBalanceReader;

  @Test
  void emptyPeriodReturnsEmpty() {
    when(glTrialBalanceReader.read(PERIOD)).thenReturn(List.of());

    Optional<IncomeStatementResponse> result =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD);

    assertThat(result).isEmpty();
  }

  @Test
  void revenueNetIsCreditMinusDebitAndExpenseNetIsDebitMinusCredit() {
    // Revenue 10 000 000 IDR credit, 0 debit → net = 10 000 000
    // Expense 4 000 000 IDR debit, 0 credit → net = 4 000 000
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(
            List.of(
                line("4000", "REVENUE", 0L, 10_000_000L, "IDR", false),
                line("5100", "EXPENSE", 4_000_000L, 0L, "IDR", false)));

    IncomeStatementResponse stmt =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD).orElseThrow();

    assertThat(stmt.totalRevenueMinor()).isEqualTo(10_000_000L);
    assertThat(stmt.totalExpenseMinor()).isEqualTo(4_000_000L);
    assertThat(stmt.netMinor()).isEqualTo(6_000_000L);
    assertThat(stmt.currency()).isEqualTo("IDR");
    assertThat(stmt.revenueLines()).hasSize(1);
    assertThat(stmt.expenseLines()).hasSize(1);
  }

  @Test
  void assetLiabilityAndEquityLinesAreExcludedFromIncomeStatement() {
    // ASSET and LIABILITY accounts in the GL trial balance are balance-sheet items; they must NOT
    // appear in the income statement lines.
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(
            List.of(
                line("4000", "REVENUE", 0L, 5_000_000L, "IDR", false),
                line("1100", "ASSET", 5_000_000L, 0L, "IDR", false), // cash account (debit)
                line("2100", "LIABILITY", 0L, 5_000_000L, "IDR", false))); // AP (credit)

    IncomeStatementResponse stmt =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD).orElseThrow();

    assertThat(stmt.revenueLines()).hasSize(1).allMatch(l -> "REVENUE".equals(l.accountType()));
    assertThat(stmt.expenseLines()).isEmpty();
    // Net = revenue − 0 expense
    assertThat(stmt.netMinor()).isEqualTo(5_000_000L);
  }

  @Test
  void netIsNegativeWhenExpenseExceedsRevenue() {
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(
            List.of(
                line("4000", "REVENUE", 0L, 2_000_000L, "IDR", false),
                line("5100", "EXPENSE", 5_000_000L, 0L, "IDR", false)));

    IncomeStatementResponse stmt =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD).orElseThrow();

    assertThat(stmt.netMinor()).isEqualTo(-3_000_000L);
  }

  @Test
  void usesIllustrativeRulesIsOrAcrossLines() {
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(
            List.of(
                line("4000", "REVENUE", 0L, 5_000_000L, "IDR", false),
                // The expense line has illustrative rules — the OR should propagate.
                line("5100", "EXPENSE", 2_000_000L, 0L, "IDR", true)));

    IncomeStatementResponse stmt =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD).orElseThrow();

    assertThat(stmt.usesIllustrativeRules()).isTrue();
  }

  @Test
  void usesIllustrativeRulesIsFalseWhenNoLineIsIllustrative() {
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(
            List.of(
                line("4000", "REVENUE", 0L, 5_000_000L, "IDR", false),
                line("5100", "EXPENSE", 2_000_000L, 0L, "IDR", false)));

    IncomeStatementResponse stmt =
        new IncomeStatementReader(glTrialBalanceReader).read(PERIOD).orElseThrow();

    assertThat(stmt.usesIllustrativeRules()).isFalse();
  }

  @Test
  void nullAccountTypeFromGlReaderThrowsGlUnmappedAccount() {
    // GlTrialBalanceReader should catch this, but the income reader has its own defence-in-depth
    // guard; with the GL reader mocked, that guard is what fires.
    when(glTrialBalanceReader.read(PERIOD))
        .thenReturn(List.of(line("MYSTERY", null, 1_000L, 0L, "IDR", false)));

    IncomeStatementReader reader = new IncomeStatementReader(glTrialBalanceReader);
    assertThatThrownBy(() -> reader.read(PERIOD))
        .isInstanceOf(GlUnmappedAccountException.class)
        .hasMessageContaining("MYSTERY");
  }

  // helpers -----------------------------------------------------------------

  private static GlTrialBalanceLineView line(
      String accountCode,
      String accountType,
      long totalDebitMinor,
      long totalCreditMinor,
      String currency,
      boolean usesIllustrative) {
    return new GlTrialBalanceLineView() {
      @Override
      public String getAccountCode() {
        return accountCode;
      }

      @Override
      public String getAccountType() {
        return accountType;
      }

      @Override
      public long getTotalDebitMinor() {
        return totalDebitMinor;
      }

      @Override
      public long getTotalCreditMinor() {
        return totalCreditMinor;
      }

      @Override
      public String getCurrency() {
        return currency;
      }

      @Override
      public boolean getUsesIllustrativeRules() {
        return usesIllustrative;
      }
    };
  }
}
