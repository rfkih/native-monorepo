package id.co.nativeapp.finance.budget.repository;

import id.co.nativeapp.finance.budget.domain.BudgetLine;
import id.co.nativeapp.finance.budget.projection.BudgetLineView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BudgetLine} (Phase 5). RLS scopes all access to the bound
 * tenant (rule 5). The detail/actuals read joins {@code chart_of_account} (global ref) for each
 * line's account name + type.
 */
public interface BudgetLineRepository extends JpaRepository<BudgetLine, UUID> {

  /**
   * The lines of a budget, joined to {@code chart_of_account} for name + type, ordered by account.
   * RLS constrains {@code budget_line} to the tenant; {@code chart_of_account} is global.
   */
  @Query(
      value =
          """
          SELECT bl.account_code AS account_code,
                 coa.name        AS account_name,
                 coa.account_type AS account_type,
                 bl.amount_minor AS amount_minor
            FROM budget_line bl
            JOIN chart_of_account coa ON coa.account_code = bl.account_code
           WHERE bl.budget_id = :budgetId
           ORDER BY bl.account_code
          """,
      nativeQuery = true)
  List<BudgetLineView> findViewsByBudgetId(@Param("budgetId") UUID budgetId);

  /** Deletes all lines of a budget (RLS-scoped) — used when deleting the budget. */
  @Modifying
  @Query(value = "DELETE FROM budget_line WHERE budget_id = :budgetId", nativeQuery = true)
  void deleteByBudgetId(@Param("budgetId") UUID budgetId);
}
