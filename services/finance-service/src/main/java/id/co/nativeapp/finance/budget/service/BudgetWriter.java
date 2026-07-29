package id.co.nativeapp.finance.budget.service;

import id.co.nativeapp.finance.budget.domain.Budget;
import id.co.nativeapp.finance.budget.domain.BudgetLine;
import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.domain.UnknownAccountException;
import id.co.nativeapp.finance.budget.repository.BudgetLineRepository;
import id.co.nativeapp.finance.budget.repository.BudgetRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for the {@link Budget} lifecycle — create and delete
 * (Phase 5, ADR 0019). A distinct proxy-invoked {@code @Component} so the {@code @Transactional}
 * advice + RLS aspect apply (rule 5). Budgets are PLANNING DATA: this writer never posts to the GL.
 * The parent-then-lines create loop mirrors {@code BillWriter.createDraft}, stamping {@code
 * company_id} on the budget and every line.
 */
@Component
public class BudgetWriter {

  private final BudgetRepository budgetRepository;
  private final BudgetLineRepository budgetLineRepository;

  public BudgetWriter(
      BudgetRepository budgetRepository, BudgetLineRepository budgetLineRepository) {
    this.budgetRepository = Objects.requireNonNull(budgetRepository, "budgetRepository");
    this.budgetLineRepository =
        Objects.requireNonNull(budgetLineRepository, "budgetLineRepository");
  }

  /**
   * Creates a budget for {@code (name, period)} with its planned lines.
   *
   * @return the new budget id
   * @throws IllegalArgumentException if there are no lines, the currency is invalid, or an account is
   *     duplicated across lines
   * @throws UnknownAccountException if a line targets an account not in the chart of accounts (→ 400)
   */
  @Transactional
  public UUID create(
      String name, String period, String currencyCode, List<BudgetLineInput> lineInputs) {
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (lineInputs == null || lineInputs.isEmpty()) {
      throw new IllegalArgumentException("a budget must have at least one line");
    }
    Currency currency = Currency.getInstance(currencyCode); // validates ISO-4217 (→ 400 if bad)
    String companyId = TenantContext.require().companyId();

    // Validate every line up front (clearer 400s than the DB FK/UNIQUE): the account must exist, and
    // no account may repeat within the budget (the uq_budget_line_account UNIQUE is the DB backstop).
    Set<String> seen = new HashSet<>();
    for (BudgetLineInput input : lineInputs) {
      String accountCode = input.accountCode();
      if (!seen.add(accountCode)) {
        throw new IllegalArgumentException("account appears more than once in the budget: " + accountCode);
      }
      if (!budgetRepository.accountExists(accountCode)) {
        throw new UnknownAccountException(accountCode);
      }
    }

    Budget budget = Budget.create(name, period, currencyCode);
    budget.setCompanyId(companyId);
    budgetRepository.save(budget);

    for (BudgetLineInput input : lineInputs) {
      Money amount = Money.ofMinor(input.amountMinor(), currency);
      BudgetLine line = BudgetLine.of(budget.getId(), input.accountCode(), amount);
      line.setCompanyId(companyId);
      budgetLineRepository.save(line);
    }
    return budget.getId();
  }

  /**
   * Deletes a budget and its lines (RLS-scoped — a foreign-tenant id is a 404).
   *
   * @throws BudgetNotFoundException if the budget is not in the bound tenant
   */
  @Transactional
  public void delete(UUID budgetId) {
    Budget budget =
        budgetRepository.findById(budgetId).orElseThrow(() -> new BudgetNotFoundException(budgetId));
    budgetLineRepository.deleteByBudgetId(budget.getId());
    budgetRepository.delete(budget);
  }
}
