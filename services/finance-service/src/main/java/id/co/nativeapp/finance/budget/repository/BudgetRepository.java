package id.co.nativeapp.finance.budget.repository;

import id.co.nativeapp.finance.budget.domain.Budget;
import id.co.nativeapp.finance.budget.projection.AccountView;
import id.co.nativeapp.finance.budget.projection.BudgetSummaryView;
import id.co.nativeapp.finance.budget.projection.BudgetView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link Budget} aggregate (Phase 5, ADR 0019).
 *
 * <p>A thin data port: no manual {@code WHERE company_id} on the budget tables — RLS scopes every
 * read/write to the bound company (rule 5). The write path (delete) uses the inherited {@code
 * findById}; the read paths use native + projection queries. The {@code chart_of_account} queries
 * hit GLOBAL reference data (not RLS) — the account catalog for the create-form picker and the
 * line-account existence check.
 */
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

  /** One budget header by id, projected — the READ (detail) path. */
  @Query(
      value =
          """
          SELECT b.id AS id, b.name AS name, b.period AS period, b.currency AS currency
            FROM budget b
           WHERE b.id = :id
          """,
      nativeQuery = true)
  Optional<BudgetView> findViewById(@Param("id") UUID id);

  /**
   * The budget list for the bound company, most-recent period first, with a rolled-up line count +
   * total planned amount. No {@code WHERE company_id} — RLS constrains the result (rule 5). Bounded
   * {@code LIMIT} (mirrors the AR/AP list readers).
   */
  @Query(
      value =
          """
          SELECT b.id                             AS id,
                 b.name                           AS name,
                 b.period                         AS period,
                 b.currency                       AS currency,
                 COUNT(bl.id)                     AS line_count,
                 COALESCE(SUM(bl.amount_minor), 0) AS total_planned_minor
            FROM budget b
            LEFT JOIN budget_line bl ON bl.budget_id = b.id
           GROUP BY b.id, b.name, b.period, b.currency
           ORDER BY b.period DESC, b.name
           LIMIT 500
          """,
      nativeQuery = true)
  List<BudgetSummaryView> findAllViews();

  /** The chart of accounts (global ref) for the create-form account picker. */
  @Query(
      value =
          """
          SELECT account_code AS account_code, name AS name, account_type AS account_type
            FROM chart_of_account
           ORDER BY account_code
          """,
      nativeQuery = true)
  List<AccountView> findAllAccounts();

  /** Whether {@code accountCode} exists in the chart of accounts (global ref) — write-path validation. */
  @Query(
      value = "SELECT count(*) > 0 FROM chart_of_account WHERE account_code = :code",
      nativeQuery = true)
  boolean accountExists(@Param("code") String accountCode);
}
