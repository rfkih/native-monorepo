package id.co.nativeapp.finance.budget.service;

import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse.ActualLine;
import id.co.nativeapp.finance.budget.projection.BudgetLineView;
import id.co.nativeapp.finance.budget.projection.BudgetView;
import id.co.nativeapp.finance.budget.repository.BudgetLineRepository;
import id.co.nativeapp.finance.budget.repository.BudgetRepository;
import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the budget-vs-actual variance report (Phase 5, ADR 0019): each budgeted account's planned
 * amount paired with its GL actual for the budget's period, and the variance ({@code actual −
 * planned}). The <em>actual</em> is the account's net in its normal direction — a revenue / liability
 * / equity credit-net, an asset / expense debit-net — from {@code glTrialBalance(period)}; an account
 * with no activity in the period actuals to zero. No new tables: budgets are compared against the
 * same GL-derived numbers the statements use.
 */
@Service
public class BudgetActualReader {

  private final BudgetRepository budgetRepository;
  private final BudgetLineRepository budgetLineRepository;
  private final GlTrialBalanceReader glTrialBalanceReader;

  public BudgetActualReader(
      BudgetRepository budgetRepository,
      BudgetLineRepository budgetLineRepository,
      GlTrialBalanceReader glTrialBalanceReader) {
    this.budgetRepository = Objects.requireNonNull(budgetRepository, "budgetRepository");
    this.budgetLineRepository =
        Objects.requireNonNull(budgetLineRepository, "budgetLineRepository");
    this.glTrialBalanceReader =
        Objects.requireNonNull(glTrialBalanceReader, "glTrialBalanceReader");
  }

  /**
   * The variance report for {@code budgetId}, or {@link BudgetNotFoundException} (→ 404) if the
   * budget is not in the bound tenant.
   *
   * @throws id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException if the period's GL is
   *     multi-currency (→ 422, from the trial-balance reader)
   */
  @Transactional(readOnly = true)
  public BudgetActualsResponse actuals(UUID budgetId) {
    BudgetView header =
        budgetRepository.findViewById(budgetId).orElseThrow(() -> new BudgetNotFoundException(budgetId));
    List<BudgetLineView> lines = budgetLineRepository.findViewsByBudgetId(budgetId);
    String currency = header.getCurrency().strip();

    List<GlTrialBalanceLineView> tbLines = glTrialBalanceReader.read(header.getPeriod());
    // The budget's currency must match the books it is compared against — else variance = actual −
    // planned would subtract two currencies (rule 8). Fail loud (→ 422) rather than compute a
    // meaningless cross-currency variance. (Unreachable via the console, which budgets in the base
    // currency; the API is nonetheless guarded.)
    if (!tbLines.isEmpty()) {
      String glCurrency = tbLines.getFirst().getCurrency().strip();
      if (!glCurrency.equals(currency)) {
        throw new GlMultiCurrencyException("budget " + budgetId, currency, glCurrency);
      }
    }

    Map<String, GlTrialBalanceLineView> actualsByAccount = new HashMap<>();
    for (GlTrialBalanceLineView tb : tbLines) {
      actualsByAccount.put(tb.getAccountCode(), tb);
    }

    List<ActualLine> actualLines = new ArrayList<>(lines.size());
    long totalPlanned = 0L;
    long totalActual = 0L;
    long totalVariance = 0L;
    boolean usesIllustrative = false;

    for (BudgetLineView line : lines) {
      long planned = line.getAmountMinor();
      GlTrialBalanceLineView tb = actualsByAccount.get(line.getAccountCode());
      long actual = tb == null ? 0L : normalizedNet(line.getAccountType(), tb);
      if (tb != null) {
        usesIllustrative = usesIllustrative || tb.getUsesIllustrativeRules();
      }
      long variance = Math.subtractExact(actual, planned);

      actualLines.add(
          new ActualLine(
              line.getAccountCode(),
              line.getAccountName(),
              line.getAccountType(),
              planned,
              actual,
              variance));
      totalPlanned = Math.addExact(totalPlanned, planned);
      totalActual = Math.addExact(totalActual, actual);
      totalVariance = Math.addExact(totalVariance, variance);
    }

    return new BudgetActualsResponse(
        header.getId(),
        header.getName(),
        header.getPeriod(),
        currency,
        List.copyOf(actualLines),
        totalPlanned,
        totalActual,
        totalVariance,
        usesIllustrative);
  }

  /**
   * The account's net for the period in its NORMAL direction: ASSET / EXPENSE are debit-normal
   * (debit − credit); REVENUE / LIABILITY / EQUITY are credit-normal (credit − debit). So a planned
   * revenue and its actual revenue are both positive-when-earned, and the variance is meaningful.
   */
  private static long normalizedNet(String accountTypeRaw, GlTrialBalanceLineView tb) {
    AccountType type = AccountType.valueOf(accountTypeRaw.toUpperCase(Locale.ROOT));
    return switch (type) {
      case ASSET, EXPENSE ->
          Math.subtractExact(tb.getTotalDebitMinor(), tb.getTotalCreditMinor());
      case LIABILITY, EQUITY, REVENUE ->
          Math.subtractExact(tb.getTotalCreditMinor(), tb.getTotalDebitMinor());
    };
  }
}
