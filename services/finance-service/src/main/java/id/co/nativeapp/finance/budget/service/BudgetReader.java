package id.co.nativeapp.finance.budget.service;

import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.dto.BudgetResponse;
import id.co.nativeapp.finance.budget.dto.BudgetResponse.BudgetLineDto;
import id.co.nativeapp.finance.budget.dto.BudgetSummaryResponse;
import id.co.nativeapp.finance.budget.projection.BudgetLineView;
import id.co.nativeapp.finance.budget.projection.BudgetView;
import id.co.nativeapp.finance.budget.repository.BudgetLineRepository;
import id.co.nativeapp.finance.budget.repository.BudgetRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for budgets (Phase 5) — the list and the detail (header + lines). Native +
 * projection reads, RLS-scoped; maps projections to DTOs so the {@code budget.projection} types never
 * leak to the controller layer.
 */
@Service
public class BudgetReader {

  private final BudgetRepository budgetRepository;
  private final BudgetLineRepository budgetLineRepository;

  public BudgetReader(
      BudgetRepository budgetRepository, BudgetLineRepository budgetLineRepository) {
    this.budgetRepository = Objects.requireNonNull(budgetRepository, "budgetRepository");
    this.budgetLineRepository =
        Objects.requireNonNull(budgetLineRepository, "budgetLineRepository");
  }

  /** The budget list for the bound tenant, most-recent period first. */
  @Transactional(readOnly = true)
  public List<BudgetSummaryResponse> list() {
    return budgetRepository.findAllViews().stream()
        .map(
            v ->
                new BudgetSummaryResponse(
                    v.getId(),
                    v.getName(),
                    v.getPeriod(),
                    v.getCurrency().strip(),
                    v.getLineCount(),
                    v.getTotalPlannedMinor()))
        .toList();
  }

  /**
   * A budget's detail (header + lines), or {@link BudgetNotFoundException} (→ 404) if not in the
   * bound tenant.
   */
  @Transactional(readOnly = true)
  public BudgetResponse detail(UUID id) {
    BudgetView header =
        budgetRepository.findViewById(id).orElseThrow(() -> new BudgetNotFoundException(id));
    List<BudgetLineDto> lines =
        budgetLineRepository.findViewsByBudgetId(id).stream()
            .map(BudgetReader::toLineDto)
            .toList();
    return new BudgetResponse(
        header.getId(), header.getName(), header.getPeriod(), header.getCurrency().strip(), lines);
  }

  private static BudgetLineDto toLineDto(BudgetLineView v) {
    return new BudgetLineDto(
        v.getAccountCode(), v.getAccountName(), v.getAccountType(), v.getAmountMinor());
  }
}
