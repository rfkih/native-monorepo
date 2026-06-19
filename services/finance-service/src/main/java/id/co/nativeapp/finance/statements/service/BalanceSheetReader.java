package id.co.nativeapp.finance.statements.service;

import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlCumulativeTrialBalanceLineView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.finance.statements.dto.BalanceSheetLineItem;
import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Balance Sheet (Laporan Posisi Keuangan) as of the end of a given period.
 *
 * <p>The balance sheet is CUMULATIVE: it sums all GL journal activity from inception up to and
 * including the as-of period. It is derived entirely from the GL (no new tables, no migrations) via
 * the {@code JournalEntryRepository#glTrialBalanceAsOf} native query, which performs a cumulative
 * aggregation ({@code WHERE journal_entry.period <= :asOfPeriod}).
 *
 * <p><strong>Retained Earnings (computed on read, never posted).</strong> The balance sheet must
 * include equity to balance. Finance's GL holds REVENUE and EXPENSE journal lines but does not post
 * a period-close retained-earnings entry. Instead, this reader computes retained earnings on the
 * fly as {@code Σ REVENUE net − Σ EXPENSE net} across all periods up to the as-of period (the
 * cumulative net income). It appends a synthetic retained-earnings equity line so the statement
 * balances. This mirrors the approach in {@code TrialBalanceReader}, which synthesises the same
 * line for the within-company close. The synthetic account code is {@value
 * #RETAINED_EARNINGS_ACCOUNT}.
 *
 * <p><strong>Balance check (defence-in-depth).</strong> After assembly the reader asserts: {@code
 * totalAssets == totalLiabilities + totalEquity (incl. retained earnings)}. Any mismatch throws
 * {@link IllegalStateException} rather than returning a silently incorrect statement.
 *
 * <p><strong>Single-currency invariant.</strong> All cumulative lines must share one currency. A
 * mixed-currency balance fails with the typed {@link GlMultiCurrencyException} (mapped to {@code
 * 422}). Multi-currency consolidation is out of scope (mirrors {@code GlTrialBalanceReader}).
 *
 * <p>Returns {@link Optional#empty()} when there are no GL entries up to the as-of period.
 */
@Service
public class BalanceSheetReader {

  /**
   * The synthetic retained-earnings equity account code. Not a posting-mapped {@code
   * chart_of_account} row — a reserved code added by this reader on every balance-sheet read to
   * represent the cumulative P&amp;L close into equity. Mirrors {@code
   * TrialBalanceReader#RETAINED_EARNINGS_ACCOUNT}.
   */
  public static final String RETAINED_EARNINGS_ACCOUNT = "3000-RETAINED-EARNINGS";

  private final JournalEntryRepository journalEntryRepository;

  public BalanceSheetReader(JournalEntryRepository journalEntryRepository) {
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
  }

  /**
   * Builds the Balance Sheet as of the end of {@code asOfPeriod} (e.g. {@code "2026-06"}).
   *
   * <p>Must be called inside a tenant-bound {@code @Transactional} scope so RLS applies (rule 5).
   *
   * @param asOfPeriod the as-of period {@code YYYY-MM}; all journal entries up to and including
   *     this period are included in the cumulative aggregation
   * @return the balance sheet, or {@link Optional#empty()} when there are no GL entries at all
   * @throws GlMultiCurrencyException if the cumulative GL is multi-currency (mapped to {@code 422})
   * @throws GlUnmappedAccountException if a line references an account with no {@code account_type}
   * @throws IllegalStateException if the assembled statement does not balance (total assets ≠ total
   *     liab + equity) — an internal posting bug
   */
  @Transactional(readOnly = true)
  public Optional<BalanceSheetResponse> read(String asOfPeriod) {
    Objects.requireNonNull(asOfPeriod, "asOfPeriod");
    List<GlCumulativeTrialBalanceLineView> lines =
        journalEntryRepository.glTrialBalanceAsOf(asOfPeriod);

    if (lines.isEmpty()) {
      return Optional.empty();
    }

    String currency = requireSingleCurrency(lines, asOfPeriod);
    boolean usesIllustrative = false;

    List<BalanceSheetLineItem> assetLines = new ArrayList<>();
    List<BalanceSheetLineItem> liabilityLines = new ArrayList<>();
    List<BalanceSheetLineItem> equityLines = new ArrayList<>();

    // P&L totals needed to compute retained earnings: cumulative revenue net and expense net.
    long cumulativeRevenueNet = 0L;
    long cumulativeExpenseNet = 0L;

    for (GlCumulativeTrialBalanceLineView line : lines) {
      String accountTypeRaw = line.getAccountType();
      if (accountTypeRaw == null) {
        throw new GlUnmappedAccountException("periods up to " + asOfPeriod, line.getAccountCode());
      }
      usesIllustrative = usesIllustrative || line.getUsesIllustrativeRules();

      AccountType accountType = AccountType.valueOf(accountTypeRaw.toUpperCase(Locale.ROOT));
      switch (accountType) {
        case ASSET -> {
          // Debit-normal: balance = totalDebit − totalCredit
          long balance = Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor());
          assetLines.add(
              new BalanceSheetLineItem(
                  line.getAccountCode(), AccountType.ASSET.name(), balance, currency));
        }
        case LIABILITY -> {
          // Credit-normal: balance = totalCredit − totalDebit
          long balance = Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor());
          liabilityLines.add(
              new BalanceSheetLineItem(
                  line.getAccountCode(), AccountType.LIABILITY.name(), balance, currency));
        }
        case EQUITY -> {
          // Credit-normal: balance = totalCredit − totalDebit
          long balance = Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor());
          equityLines.add(
              new BalanceSheetLineItem(
                  line.getAccountCode(), AccountType.EQUITY.name(), balance, currency));
        }
        case REVENUE -> {
          // Credit-normal: net contribution = totalCredit − totalDebit (cumulative)
          long net = Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor());
          cumulativeRevenueNet = Math.addExact(cumulativeRevenueNet, net);
        }
        case EXPENSE -> {
          // Debit-normal: net cost = totalDebit − totalCredit (cumulative)
          long net = Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor());
          cumulativeExpenseNet = Math.addExact(cumulativeExpenseNet, net);
        }
        default ->
            throw new IllegalStateException(
                "Unhandled AccountType: "
                    + accountType
                    + " — update BalanceSheetReader to cover it");
      }
    }

    // Retained earnings = cumulative net income = Σ revenue net − Σ expense net.
    // This is the P&L close into equity — computed on read, never posted.
    // Mirrors TrialBalanceReader's approach for the within-company close.
    long retainedEarnings = Math.subtractExact(cumulativeRevenueNet, cumulativeExpenseNet);
    equityLines.add(
        new BalanceSheetLineItem(
            RETAINED_EARNINGS_ACCOUNT, AccountType.EQUITY.name(), retainedEarnings, currency));

    long totalAssets =
        assetLines.stream()
            .mapToLong(BalanceSheetLineItem::balanceMinor)
            .reduce(0L, Math::addExact);
    long totalLiabilities =
        liabilityLines.stream()
            .mapToLong(BalanceSheetLineItem::balanceMinor)
            .reduce(0L, Math::addExact);
    long totalEquity =
        equityLines.stream()
            .mapToLong(BalanceSheetLineItem::balanceMinor)
            .reduce(0L, Math::addExact);
    long totalLiabAndEquity = Math.addExact(totalLiabilities, totalEquity);

    // Defence-in-depth balance check: total assets must equal total liabilities + equity.
    // A mismatch indicates a bug in the GL posting or the retained-earnings computation.
    if (totalAssets != totalLiabAndEquity) {
      throw new IllegalStateException(
          "Balance Sheet as of "
              + asOfPeriod
              + " does NOT balance: totalAssets="
              + totalAssets
              + " totalLiabilitiesAndEquity="
              + totalLiabAndEquity
              + " (diff="
              + (totalAssets - totalLiabAndEquity)
              + ") — this indicates a GL posting bug or an unmapped account");
    }

    return Optional.of(
        new BalanceSheetResponse(
            asOfPeriod,
            currency,
            List.copyOf(assetLines),
            List.copyOf(liabilityLines),
            List.copyOf(equityLines),
            totalAssets,
            totalLiabilities,
            totalEquity,
            totalLiabAndEquity,
            retainedEarnings,
            usesIllustrative));
  }

  /**
   * Derives the single currency from the cumulative lines, failing loud if more than one is found.
   * Mirrors {@code GlTrialBalanceReader#assertSingleCurrency}.
   */
  private static String requireSingleCurrency(
      List<GlCumulativeTrialBalanceLineView> lines, String asOfPeriod) {
    String first = lines.getFirst().getCurrency().strip();
    for (GlCumulativeTrialBalanceLineView line : lines) {
      String currency = line.getCurrency().strip();
      if (!currency.equals(first)) {
        throw new GlMultiCurrencyException("periods up to " + asOfPeriod, first, currency);
      }
    }
    return first;
  }
}
