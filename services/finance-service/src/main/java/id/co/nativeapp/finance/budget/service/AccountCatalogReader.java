package id.co.nativeapp.finance.budget.service;

import id.co.nativeapp.finance.budget.dto.AccountResponse;
import id.co.nativeapp.finance.budget.repository.BudgetRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes the chart of accounts (code + name + type) for the budget create-form account picker
 * (Phase 5). {@code chart_of_account} is GLOBAL reference data — the same accounts for every tenant —
 * so this returns the full catalog (no tenant scoping); it is served only to owner/manager at the
 * gateway.
 */
@Service
public class AccountCatalogReader {

  private final BudgetRepository budgetRepository;

  public AccountCatalogReader(BudgetRepository budgetRepository) {
    this.budgetRepository = Objects.requireNonNull(budgetRepository, "budgetRepository");
  }

  /** Every chart-of-account account, ordered by code. */
  @Transactional(readOnly = true)
  public List<AccountResponse> list() {
    return budgetRepository.findAllAccounts().stream()
        .map(v -> new AccountResponse(v.getAccountCode(), v.getName(), v.getAccountType()))
        .toList();
  }
}
