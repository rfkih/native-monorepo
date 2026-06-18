package id.co.nativeapp.finance.withinclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.finance.revenue.projection.TrialBalanceLineView;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.withinclose.domain.BaseCurrencyMismatchException;
import id.co.nativeapp.finance.withinclose.domain.MultiCurrencyTrialBalanceException;
import id.co.nativeapp.finance.withinclose.domain.UndeterminableBaseCurrencyException;
import id.co.nativeapp.finance.withinclose.domain.UnmappedLedgerAccountException;
import id.co.nativeapp.finance.withinclose.service.CompanyTrialBalance;
import id.co.nativeapp.finance.withinclose.service.TrialBalanceReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit proofs for {@link TrialBalanceReader} (no Spring / no Testcontainers — the slow finance
 * integration path is exercised by {@link id.co.nativeapp.finance.WithinCompanyCloseTest}). The
 * {@link LedgerPostingRepository} is mocked so the aggregation projection can be shaped directly,
 * locking three review-finding behaviours by construction:
 *
 * <ul>
 *   <li><strong>FIX 1</strong> — the base currency is DERIVED from the ledger postings; a declared
 *       (body) currency is only a cross-check that fails loud on mismatch, and an empty period with
 *       no declared currency fails loud rather than fabricating one.
 *   <li><strong>FIX 2</strong> — a posting whose {@code account_type} is {@code NULL} (an unmapped
 *       {@code gl_account_code} the {@code LEFT JOIN} surfaces) FAILS the close, never silently
 *       dropping the money.
 *   <li><strong>FIX 4</strong> — a labor REVERSAL period (a net-negative-expense gl group) still
 *       publishes a trial balance that sums signed-to-zero: the equity closing line equals the
 *       genuine net even when a stored magnitude is negative.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TrialBalanceReaderTest {

  private static final String PERIOD = "2026-06";

  @Mock private LedgerPostingRepository ledgerRepository;

  // FIX 1 -------------------------------------------------------------------

  @Test
  void derivesTheBaseCurrencyFromTheLedgerNotTheBody() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(
            List.of(
                row("4000", "REVENUE", "REVENUE", 10_000_000L, "IDR", false),
                row("5100", "EXPENSE", "EXPENSE", 4_000_000L, "IDR", false)));

    // No declared currency: the reader derives IDR from the postings.
    TrialBalanceReader reader = new TrialBalanceReader(ledgerRepository);
    CompanyTrialBalance tb = reader.gather(PERIOD, null);

    assertThat(tb.baseCurrency()).isEqualTo("IDR");
    assertThat(signed(tb)).isZero();
  }

  @Test
  void aDeclaredCurrencyMatchingTheLedgerIsAcceptedAsACrossCheck() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(List.of(row("4000", "REVENUE", "REVENUE", 5_000_000L, "IDR", false)));

    CompanyTrialBalance tb = new TrialBalanceReader(ledgerRepository).gather(PERIOD, "IDR");

    assertThat(tb.baseCurrency()).isEqualTo("IDR");
  }

  @Test
  void aDeclaredCurrencyMismatchingTheLedgerFailsLoud() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(List.of(row("4000", "REVENUE", "REVENUE", 5_000_000L, "IDR", false)));

    TrialBalanceReader reader = new TrialBalanceReader(ledgerRepository);
    // The body claims USD but the books are in IDR — the immutable base currency is the ledger's.
    assertThatThrownBy(() -> reader.gather(PERIOD, "USD"))
        .isInstanceOf(BaseCurrencyMismatchException.class)
        .hasMessageContaining("USD")
        .hasMessageContaining("IDR");
  }

  @Test
  void anEmptyPeriodWithNoDeclaredCurrencyFailsLoudRatherThanFabricatingOne() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD)).thenReturn(List.of());

    TrialBalanceReader reader = new TrialBalanceReader(ledgerRepository);
    assertThatThrownBy(() -> reader.gather(PERIOD, null))
        .isInstanceOf(UndeterminableBaseCurrencyException.class);
  }

  @Test
  void anEmptyPeriodRecordsTheDeclaredCrossCheckCurrencyForAudit() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD)).thenReturn(List.of());

    CompanyTrialBalance tb = new TrialBalanceReader(ledgerRepository).gather(PERIOD, "IDR");

    assertThat(tb.isEmpty()).isTrue();
    assertThat(tb.baseCurrency()).isEqualTo("IDR");
  }

  @Test
  void aMultiCurrencyPeriodFailsLoudWithATypedFault() {
    // #42: a period whose postings span TWO currencies is malformed input the close cannot roll up
    // (multi-currency within one company needs FX). It must surface as a TYPED MultiCurrencyTrial
    // BalanceException (mapped to 422), NOT a bare IllegalStateException (a generic 500).
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(
            List.of(
                row("4000", "REVENUE", "REVENUE", 10_000_000L, "IDR", false),
                row("4001", "REVENUE", "REVENUE", 5_000L, "USD", false)));

    TrialBalanceReader reader = new TrialBalanceReader(ledgerRepository);
    assertThatThrownBy(() -> reader.gather(PERIOD, null))
        .isInstanceOf(MultiCurrencyTrialBalanceException.class)
        .hasMessageContaining("IDR")
        .hasMessageContaining("USD");
  }

  // #42 zero-net P&L --------------------------------------------------------

  @Test
  void aPeriodWhosePnlNetsToZeroAppendsNoEquityLineYetStillSumsSignedToZero() {
    // #42: a period whose P&L nets to EXACTLY zero (revenue credit cancels expense debit) appends
    // NO
    // retained-earnings equity closing line, yet the published trial balance still sums signed-to-
    // zero. This locks that the equity line is appended CONDITIONALLY on a non-zero net — a future
    // refactor that unconditionally appends it (double-counting the now-zero net) would break the
    // "no line" assertion below.
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(
            List.of(
                row("4000", "REVENUE", "REVENUE", 6_000_000L, "IDR", false),
                row("5100", "EXPENSE", "EXPENSE", 6_000_000L, "IDR", false)));

    CompanyTrialBalance tb = new TrialBalanceReader(ledgerRepository).gather(PERIOD, "IDR");

    // The signed double-entry residual is zero with NO equity closing line synthesized.
    assertThat(signed(tb)).isZero();
    assertThat(tb.lines()).noneMatch(l -> "EQUITY".equals(l.accountType()));
    // Exactly the two original P&L lines, no synthesized closing line.
    assertThat(tb.lines()).hasSize(2);
  }

  // FIX 2 -------------------------------------------------------------------

  @Test
  void anUnmappedAccountFailsLoudAndNeverSilentlyDropsTheMoney() {
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(
            List.of(
                row("4000", "REVENUE", "REVENUE", 10_000_000L, "IDR", false),
                // A posting whose gl_account_code has NO chart_of_account row: the LEFT JOIN yields
                // a
                // NULL account_type. An INNER JOIN would have dropped this 9,999,999 silently while
                // the equity line still balanced to the understated net.
                row("8888-MYSTERY", null, "EXPENSE", 9_999_999L, "IDR", false)));

    TrialBalanceReader reader = new TrialBalanceReader(ledgerRepository);
    assertThatThrownBy(() -> reader.gather(PERIOD, "IDR"))
        .isInstanceOf(UnmappedLedgerAccountException.class)
        .hasMessageContaining("8888-MYSTERY");
  }

  // FIX 4 -------------------------------------------------------------------

  @Test
  void aLaborReversalNetNegativeExpenseGroupStillSumsSignedToZero() {
    // A period whose labor gl group (6000 Salaries) nets NEGATIVE: a prior run's PRIMARY (+5M) was
    // superseded and over-reversed by a correction so the period's signed SUM for 6000 is -2M (a
    // net-negative-expense magnitude the projection carries verbatim). The genuine net P&L is then
    // REVENUE(-) over a NEGATIVE expense, and the equity closing line must still drive the signed
    // residual to zero even with a negative stored magnitude.
    when(ledgerRepository.trialBalanceForPeriod(PERIOD))
        .thenReturn(
            List.of(
                row("4000", "REVENUE", "REVENUE", 10_000_000L, "IDR", false),
                row("5100", "EXPENSE", "EXPENSE", 4_000_000L, "IDR", false),
                // The labor reversal leaves account 6000 with a NEGATIVE net expense magnitude.
                row("6000", "EXPENSE", "EXPENSE", -2_000_000L, "IDR", false)));

    CompanyTrialBalance tb = new TrialBalanceReader(ledgerRepository).gather(PERIOD, "IDR");

    // The published trial balance sums signed-to-zero DESPITE the negative expense magnitude.
    assertThat(signed(tb)).isZero();

    // The genuine net P&L = signed P&L residual = (EXPENSE 4M + EXPENSE -2M) - REVENUE 10M = -8M,
    // so
    // the equity closing magnitude is the TRUE net (-8M), not a figure that ignored the reversal.
    long equity =
        tb.lines().stream()
            .filter(l -> "EQUITY".equals(l.accountType()))
            .mapToLong(TrialBalanceLine::amountMinor)
            .sum();
    assertThat(equity).isEqualTo(-8_000_000L);
  }

  // helpers -----------------------------------------------------------------

  /** The signed double-entry residual of the published lines (DEBIT +, CREDIT -). */
  private static long signed(CompanyTrialBalance tb) {
    long total = 0;
    for (TrialBalanceLine line : tb.lines()) {
      AccountType type = AccountType.valueOf(line.accountType());
      total +=
          switch (type) {
            case ASSET, EXPENSE -> line.amountMinor();
            case LIABILITY, EQUITY, REVENUE -> -line.amountMinor();
          };
    }
    return total;
  }

  private static TrialBalanceLineView row(
      String glAccountCode,
      String accountType,
      String postingType,
      long amountMinor,
      String currency,
      boolean usesIllustrative) {
    return new TrialBalanceLineView() {
      @Override
      public String getGlAccountCode() {
        return glAccountCode;
      }

      @Override
      public String getAccountType() {
        return accountType;
      }

      @Override
      public String getPostingType() {
        return postingType;
      }

      @Override
      public long getAmountMinor() {
        return amountMinor;
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
