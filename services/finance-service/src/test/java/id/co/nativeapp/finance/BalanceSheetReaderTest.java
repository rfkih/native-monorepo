package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlCumulativeTrialBalanceLineView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import id.co.nativeapp.finance.statements.service.BalanceSheetReader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit proofs for {@link BalanceSheetReader} — no Spring, no Testcontainers. The {@link
 * JournalEntryRepository} is mocked so the cumulative trial balance can be shaped directly.
 *
 * <p>Verifies: (a) totalAssets == totalLiabilities + totalEquity incl. retained earnings; (b)
 * retained earnings = cumulative revenue net − cumulative expense net; (c) REVENUE/EXPENSE lines
 * are excluded from the balance-sheet line lists (folded into retained earnings instead); (d) empty
 * cumulative GL → Optional.empty(); (e) multi-currency fails loud; (f) unmapped account type fails
 * loud; (g) usesIllustrativeRules OR-ed across all lines.
 */
@ExtendWith(MockitoExtension.class)
class BalanceSheetReaderTest {

  private static final String AS_OF = "2026-06";

  @Mock private JournalEntryRepository journalEntryRepository;

  @Test
  void emptyGlReturnsEmpty() {
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF)).thenReturn(List.of());

    Optional<BalanceSheetResponse> result =
        new BalanceSheetReader(journalEntryRepository).read(AS_OF);

    assertThat(result).isEmpty();
  }

  @Test
  void balancedGlWithRevenueExpenseAndAssetProducesRetainedEarningsAndBalances() {
    // A simple double-entry scenario:
    //   Sale 10 000 000 IDR:  DR 1100-Cash 10M / CR 4000-Revenue 10M
    //   Expense 4 000 000 IDR: DR 5100-OpEx 4M  / CR 1100-Cash   4M
    // Cumulative lines:
    //   1100-Cash (ASSET):   debit 10M, credit 4M → balance = 6M
    //   4000-Revenue (REV):  credit 10M, debit 0  → revenue net = 10M
    //   5100-OpEx (EXP):     debit 4M,  credit 0  → expense net = 4M
    // Retained earnings = 10M − 4M = 6M (equity)
    // Total assets = 6M; Total equity = 6M; Total liab = 0
    // Balance check: 6M == 0 + 6M ✓
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(
            List.of(
                line("1100", "ASSET", 10_000_000L, 4_000_000L, "IDR", false),
                line("4000", "REVENUE", 0L, 10_000_000L, "IDR", false),
                line("5100", "EXPENSE", 4_000_000L, 0L, "IDR", false)));

    BalanceSheetResponse bs =
        new BalanceSheetReader(journalEntryRepository).read(AS_OF).orElseThrow();

    assertThat(bs.currency()).isEqualTo("IDR");
    assertThat(bs.totalAssetsMinor()).isEqualTo(6_000_000L);
    assertThat(bs.totalLiabilitiesMinor()).isZero();
    assertThat(bs.retainedEarningsMinor()).isEqualTo(6_000_000L);
    assertThat(bs.totalEquityMinor()).isEqualTo(6_000_000L);
    assertThat(bs.totalLiabilitiesAndEquityMinor()).isEqualTo(6_000_000L);
    // The fundamental accounting equation must hold.
    assertThat(bs.totalAssetsMinor()).isEqualTo(bs.totalLiabilitiesAndEquityMinor());
    // Revenue and expense must NOT appear as separate balance-sheet line items.
    assertThat(bs.assetLines()).hasSize(1);
    assertThat(bs.liabilityLines()).isEmpty();
    // Equity has the retained-earnings line only.
    assertThat(bs.equityLines()).hasSize(1);
    assertThat(bs.equityLines().getFirst().accountCode())
        .isEqualTo(BalanceSheetReader.RETAINED_EARNINGS_ACCOUNT);
  }

  @Test
  void retainedEarningsIsNegativeWhenCumulativeExpenseExceedsRevenue() {
    // Revenue 2M, expense 5M → retained earnings = −3M (accumulated loss).
    // Asset debit 2M, credit 5M → balance = −3M (overdraft — abnormal but arithmetically valid).
    // Total liab = 0, total equity = −3M → liab+equity = −3M → balances ✓
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(
            List.of(
                line("1100", "ASSET", 2_000_000L, 5_000_000L, "IDR", false),
                line("4000", "REVENUE", 0L, 2_000_000L, "IDR", false),
                line("5100", "EXPENSE", 5_000_000L, 0L, "IDR", false)));

    BalanceSheetResponse bs =
        new BalanceSheetReader(journalEntryRepository).read(AS_OF).orElseThrow();

    assertThat(bs.retainedEarningsMinor()).isEqualTo(-3_000_000L);
    assertThat(bs.totalAssetsMinor()).isEqualTo(-3_000_000L);
    assertThat(bs.totalAssetsMinor()).isEqualTo(bs.totalLiabilitiesAndEquityMinor());
  }

  @Test
  void existingEquityLinesAreIncludedAlongsideRetainedEarnings() {
    // Paid-in capital equity (CR 5M) + revenue (CR 3M) → RE = 3M, total equity = 8M.
    // Asset DR 8M → balance = 8M. Balances ✓
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(
            List.of(
                line("1100", "ASSET", 8_000_000L, 0L, "IDR", false),
                line("3100", "EQUITY", 0L, 5_000_000L, "IDR", false),
                line("4000", "REVENUE", 0L, 3_000_000L, "IDR", false)));

    BalanceSheetResponse bs =
        new BalanceSheetReader(journalEntryRepository).read(AS_OF).orElseThrow();

    assertThat(bs.totalAssetsMinor()).isEqualTo(8_000_000L);
    assertThat(bs.retainedEarningsMinor()).isEqualTo(3_000_000L);
    assertThat(bs.totalEquityMinor()).isEqualTo(8_000_000L); // 5M capital + 3M RE
    assertThat(bs.totalLiabilitiesAndEquityMinor()).isEqualTo(8_000_000L);
    assertThat(bs.totalAssetsMinor()).isEqualTo(bs.totalLiabilitiesAndEquityMinor());
    // Two equity lines: 3100-capital + retained-earnings
    assertThat(bs.equityLines()).hasSize(2);
  }

  @Test
  void multiCurrencyGlFailsLoud() {
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(
            List.of(
                line("1100", "ASSET", 5_000_000L, 0L, "IDR", false),
                line("4000", "REVENUE", 0L, 500L, "USD", false)));

    BalanceSheetReader reader = new BalanceSheetReader(journalEntryRepository);
    assertThatThrownBy(() -> reader.read(AS_OF))
        .isInstanceOf(GlMultiCurrencyException.class)
        .hasMessageContaining("IDR")
        .hasMessageContaining("USD");
  }

  @Test
  void unmappedAccountTypeFailsLoud() {
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(List.of(line("9999-MYSTERY", null, 1_000L, 0L, "IDR", false)));

    BalanceSheetReader reader = new BalanceSheetReader(journalEntryRepository);
    assertThatThrownBy(() -> reader.read(AS_OF))
        .isInstanceOf(GlUnmappedAccountException.class)
        .hasMessageContaining("9999-MYSTERY");
  }

  @Test
  void usesIllustrativeRulesIsOrAcrossAllLines() {
    when(journalEntryRepository.glTrialBalanceAsOf(AS_OF))
        .thenReturn(
            List.of(
                line("1100", "ASSET", 3_000_000L, 0L, "IDR", false),
                line("4000", "REVENUE", 0L, 3_000_000L, "IDR", true)));

    BalanceSheetResponse bs =
        new BalanceSheetReader(journalEntryRepository).read(AS_OF).orElseThrow();

    assertThat(bs.usesIllustrativeRules()).isTrue();
  }

  // helpers -----------------------------------------------------------------

  private static GlCumulativeTrialBalanceLineView line(
      String accountCode,
      String accountType,
      long totalDebitMinor,
      long totalCreditMinor,
      String currency,
      boolean usesIllustrative) {
    return new GlCumulativeTrialBalanceLineView() {
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
