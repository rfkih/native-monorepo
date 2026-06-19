package id.co.nativeapp.finance.statements.service;

import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.finance.statements.dto.IncomeLineItem;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Income Statement (Laba Rugi) for a given period from the double-entry GL trial balance.
 *
 * <p>The income statement is PERIOD-scoped: it covers only the journal entries whose {@code period}
 * equals the requested {@code YYYY-MM}. It is derived from the GL trial balance via {@link
 * GlTrialBalanceReader#read} — no new tables, no migrations.
 *
 * <p><strong>Sign convention.</strong> REVENUE is credit-normal: net = totalCredit − totalDebit.
 * EXPENSE is debit-normal: net = totalDebit − totalCredit. Net profit = Σ revenue nets − Σ expense
 * nets. Other account types (ASSET, LIABILITY, EQUITY) are excluded from the income statement —
 * they belong on the Balance Sheet.
 *
 * <p>Returns {@link Optional#empty()} for a period with no GL entries, rather than an empty
 * statement with an unknown currency (mirrors the PnlController / RevenueController pattern for the
 * "no data, no currency hint" case).
 *
 * <p>Must be called inside a tenant-bound {@code @Transactional} scope so RLS applies (rule 5).
 */
@Service
public class IncomeStatementReader {

  private final GlTrialBalanceReader glTrialBalanceReader;

  public IncomeStatementReader(GlTrialBalanceReader glTrialBalanceReader) {
    this.glTrialBalanceReader =
        Objects.requireNonNull(glTrialBalanceReader, "glTrialBalanceReader");
  }

  /**
   * Builds the Income Statement for {@code period} (e.g. {@code "2026-06"}).
   *
   * <p>Delegates the GL read and the Σdebit==Σcredit + single-currency assertions to {@link
   * GlTrialBalanceReader#read}, then classifies lines as REVENUE or EXPENSE (skipping other account
   * types that do not belong in a P&amp;L, e.g. ASSET / LIABILITY / EQUITY clearing entries).
   *
   * @param period the accounting period {@code YYYY-MM}
   * @return the income statement, or {@link Optional#empty()} when the period has no GL entries
   * @throws id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException if the GL is multi-currency
   *     (propagated from the reader; mapped to {@code 422})
   * @throws GlUnmappedAccountException defence-in-depth if a line has no {@code account_type}
   * @throws IllegalStateException if the GL is unbalanced (propagated from the reader; internal
   *     500)
   */
  @Transactional(readOnly = true)
  public Optional<IncomeStatementResponse> read(String period) {
    Objects.requireNonNull(period, "period");
    List<GlTrialBalanceLineView> lines = glTrialBalanceReader.read(period);
    if (lines.isEmpty()) {
      return Optional.empty();
    }

    String currency = lines.getFirst().getCurrency().strip();
    List<IncomeLineItem> revenueLines = new ArrayList<>();
    List<IncomeLineItem> expenseLines = new ArrayList<>();
    boolean usesIllustrative = false;

    for (GlTrialBalanceLineView line : lines) {
      String accountTypeRaw = line.getAccountType();
      if (accountTypeRaw == null) {
        // GlTrialBalanceReader already asserts no nulls; this is a defence-in-depth guard.
        throw new GlUnmappedAccountException("period " + period, line.getAccountCode());
      }
      usesIllustrative = usesIllustrative || line.getUsesIllustrativeRules();

      AccountType accountType = AccountType.valueOf(accountTypeRaw.toUpperCase(Locale.ROOT));
      switch (accountType) {
        case REVENUE -> {
          // Credit-normal: net = credit − debit (positive = income)
          long net = Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor());
          revenueLines.add(
              new IncomeLineItem(line.getAccountCode(), AccountType.REVENUE.name(), net, currency));
        }
        case EXPENSE -> {
          // Debit-normal: net = debit − credit (positive = cost)
          long net = Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor());
          expenseLines.add(
              new IncomeLineItem(line.getAccountCode(), AccountType.EXPENSE.name(), net, currency));
        }
        default -> {
          // ASSET, LIABILITY, EQUITY — not part of the income statement; skip.
        }
      }
    }

    long totalRevenue =
        revenueLines.stream().mapToLong(IncomeLineItem::netMinor).reduce(0L, Math::addExact);
    long totalExpense =
        expenseLines.stream().mapToLong(IncomeLineItem::netMinor).reduce(0L, Math::addExact);
    long net = Math.subtractExact(totalRevenue, totalExpense);

    return Optional.of(
        new IncomeStatementResponse(
            period,
            currency,
            List.copyOf(revenueLines),
            List.copyOf(expenseLines),
            totalRevenue,
            totalExpense,
            net,
            usesIllustrative));
  }
}
