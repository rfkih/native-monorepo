package id.co.nativeapp.finance.gl.service;

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
 * <p>Both are fail-loud ({@link IllegalStateException}) rather than silent: the GL is the source of
 * truth for financial statements; a quietly-unbalanced GL is worse than a loud error.
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
   * @throws IllegalStateException if Σdebits ≠ Σcredits or more than one currency is present
   */
  @Transactional(readOnly = true)
  public List<GlTrialBalanceLineView> read(String period) {
    Objects.requireNonNull(period, "period");
    List<GlTrialBalanceLineView> lines = journalEntryRepository.glTrialBalance(period);
    if (lines.isEmpty()) {
      return lines;
    }
    assertSingleCurrency(lines, period);
    assertBalanced(lines, period);
    return lines;
  }

  private static void assertSingleCurrency(List<GlTrialBalanceLineView> lines, String period) {
    String firstCurrency = lines.getFirst().getCurrency().strip();
    for (GlTrialBalanceLineView line : lines) {
      String currency = line.getCurrency().strip();
      if (!currency.equals(firstCurrency)) {
        throw new IllegalStateException(
            "GL trial balance for period "
                + period
                + " contains multiple currencies ("
                + firstCurrency
                + " and "
                + currency
                + ") — FX is out of scope; this indicates a data-integrity problem");
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
