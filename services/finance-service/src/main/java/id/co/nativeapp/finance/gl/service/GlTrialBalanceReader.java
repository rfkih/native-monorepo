package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for the double-entry GL trial balance — mirrors {@code TrialBalanceReader}
 * in structure and fail-loud discipline.
 *
 * <p>Asserts two invariants that must hold for every correctly posted period:
 *
 * <ol>
 *   <li><strong>Σdebit == Σcredit</strong> — the fundamental double-entry balance. Any mismatch
 *       indicates a bug in {@link JournalPostingService} or a direct DB mutation, never a valid
 *       state.
 *   <li><strong>Single currency</strong> — all lines in the period share one ISO-4217 code. A mixed
 *       currency would require FX, which is out of scope; fail loud so the operator notices.
 * </ol>
 *
 * <p>All are fail-loud rather than silent: the GL is the source of truth for financial statements;
 * a quietly-unbalanced GL is worse than a loud error. A multi-currency trial balance throws the
 * typed {@link GlMultiCurrencyException} (mapped to {@code 422}); an unmapped account throws the
 * typed {@link GlUnmappedAccountException} and an internal Σdebit≠Σcredit imbalance throws {@link
 * IllegalStateException} — both internal data-integrity faults that surface as a non-leaking {@code
 * 500}.
 */
@Service
public class GlTrialBalanceReader {

  private final JournalEntryRepository journalEntryRepository;

  public GlTrialBalanceReader(JournalEntryRepository journalEntryRepository) {
    this.journalEntryRepository = Objects.requireNonNull(journalEntryRepository);
  }

  /**
   * Returns the GL trial balance for {@code period}, asserting Σdebits == Σcredits and
   * single-currency. Must be called inside a tenant-bound {@code @Transactional} scope so RLS
   * applies.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @return the trial-balance lines (may be empty for a period with no journal entries)
   * @throws GlMultiCurrencyException if more than one currency is present (mapped to {@code 422})
   * @throws GlUnmappedAccountException if a line has no resolvable {@code account_type}
   * @throws IllegalStateException if Σdebits ≠ Σcredits (an internal posting bug)
   */
  @Transactional(readOnly = true)
  public List<GlTrialBalanceLineView> read(String period) {
    Objects.requireNonNull(period, "period");
    List<GlTrialBalanceLineView> lines = journalEntryRepository.glTrialBalance(period);
    if (lines.isEmpty()) {
      return lines;
    }
    assertAllAccountsMapped(lines, period);
    assertSingleCurrency(lines, period);
    assertBalanced(lines, period);
    return lines;
  }

  /**
   * Fail loud if any line references an account with no {@code account_type} in {@code
   * chart_of_account}. The repository uses a LEFT JOIN precisely so an orphan account surfaces as a
   * NULL {@code account_type} here rather than being silently dropped from the aggregate; a NULL
   * means the chart/seed is incomplete and any statement derived from this trial balance would
   * misclassify the account. Mirrors {@code TrialBalanceReader}'s unmapped-account guard.
   */
  private static void assertAllAccountsMapped(List<GlTrialBalanceLineView> lines, String period) {
    for (GlTrialBalanceLineView line : lines) {
      if (line.getAccountType() == null) {
        throw new GlUnmappedAccountException("period " + period, line.getAccountCode());
      }
    }
  }

  private static void assertSingleCurrency(List<GlTrialBalanceLineView> lines, String period) {
    String firstCurrency = lines.getFirst().getCurrency().strip();
    for (GlTrialBalanceLineView line : lines) {
      String currency = line.getCurrency().strip();
      if (!currency.equals(firstCurrency)) {
        throw new GlMultiCurrencyException("period " + period, firstCurrency, currency);
      }
    }
  }

  private static void assertBalanced(List<GlTrialBalanceLineView> lines, String period) {
    long totalDebit = 0L;
    long totalCredit = 0L;
    for (GlTrialBalanceLineView line : lines) {
      totalDebit = Math.addExact(totalDebit, line.getTotalDebitMinor());
      totalCredit = Math.addExact(totalCredit, line.getTotalCreditMinor());
    }
    if (totalDebit != totalCredit) {
      throw new IllegalStateException(
          "GL trial balance for period "
              + period
              + " is NOT balanced: Σdebits="
              + totalDebit
              + " Σcredits="
              + totalCredit
              + " — this indicates a bug in JournalPostingService or a direct DB mutation");
    }
  }
}
