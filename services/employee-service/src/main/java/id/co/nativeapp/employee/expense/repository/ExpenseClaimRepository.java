package id.co.nativeapp.employee.expense.repository;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.projection.ExpenseClaimSummaryView;
import id.co.nativeapp.employee.expense.projection.MyExpenseClaimView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link ExpenseClaim} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). The full entity is loaded only on the write path
 * via the inherited {@code findById}/{@code save}; every read here is a native-query projection
 * selecting only the columns the caller needs (CODE-STRUCTURE §3.3).
 */
public interface ExpenseClaimRepository extends JpaRepository<ExpenseClaim, UUID> {

  /** The caller's own claims, newest-updated first, capped at 200 (v1, page-less). */
  @Query(
      value =
          """
          SELECT c.id AS id, c.status AS status, c.amount_minor AS amount_minor,
                 c.amount_currency AS amount_currency, c.expense_date AS expense_date,
                 c.merchant AS merchant, cat.name AS category_name,
                 c.reimbursement_method AS reimbursement_method, c.decided_by AS decided_by,
                 c.decided_at AS decided_at, c.decision_comment AS decision_comment
            FROM expense_claim c
            JOIN expense_category cat ON cat.id = c.category_id
           WHERE c.employee_id = :employeeId
           ORDER BY c.updated_at DESC
           LIMIT 200
          """,
      nativeQuery = true)
  List<MyExpenseClaimView> findMyClaims(@Param("employeeId") UUID employeeId);

  /**
   * The manager-facing claim list: optional status filter + optional org-unit scope, newest-
   * updated first, capped at 200 (v1, page-less). {@code orgUnitIds} follows the {@code
   * EmployeeRepository#findListRows} idiom — when {@code hasUnits} is {@code false} the caller
   * passes a non-empty sentinel list (never a real empty IN-list, which is invalid native SQL) and
   * the {@code OR} short-circuits the scope predicate.
   */
  @Query(
      value =
          """
          SELECT c.id AS id, c.employee_id AS employee_id, e.full_name AS employee_name,
                 c.status AS status, c.amount_minor AS amount_minor,
                 c.amount_currency AS amount_currency, c.expense_date AS expense_date,
                 c.merchant AS merchant, cat.name AS category_name, c.org_unit_id AS org_unit_id,
                 c.reimbursement_method AS reimbursement_method, c.decided_by AS decided_by,
                 c.decided_at AS decided_at, c.decision_comment AS decision_comment
            FROM expense_claim c
            JOIN employee e ON e.id = c.employee_id
            JOIN expense_category cat ON cat.id = c.category_id
           WHERE (CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))
             AND (:hasUnits = FALSE OR c.org_unit_id IN (:orgUnitIds))
           ORDER BY c.updated_at DESC
           LIMIT 200
          """,
      nativeQuery = true)
  List<ExpenseClaimSummaryView> findForManager(
      @Param("status") String status,
      @Param("hasUnits") boolean hasUnits,
      @Param("orgUnitIds") List<UUID> orgUnitIds);

  /**
   * Any one claim's currency, oldest-first — the tenant-currency-consistency probe {@code
   * ExpenseClaimWriter} uses (v1 has no other stored source of a company base currency; see its
   * Javadoc). A lightweight single-scalar probe, in the same spirit as a {@code count}/{@code
   * exists} check (CODE-STRUCTURE §3.3) rather than a display read, so it is not a dedicated
   * projection interface.
   */
  @Query(
      value = "SELECT amount_currency FROM expense_claim ORDER BY created_at LIMIT 1",
      nativeQuery = true)
  Optional<String> findAnyCurrency();
}
