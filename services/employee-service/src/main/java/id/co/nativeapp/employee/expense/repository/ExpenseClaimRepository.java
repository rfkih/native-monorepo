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

  /**
   * One page of the caller's own claims, newest-updated first (ENGINEERING-STANDARDS §1.3 — an
   * envelope, never a bare unbounded list). {@code size}/{@code offset} are pre-bounded by {@code
   * ExpenseClaimReader} (size capped, page floored at 0) before reaching here.
   */
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
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<MyExpenseClaimView> findMyClaims(
      @Param("employeeId") UUID employeeId, @Param("size") int size, @Param("offset") long offset);

  /** The total count backing {@link #findMyClaims} (the envelope's {@code totalElements}). */
  @Query(
      value = "SELECT COUNT(*) FROM expense_claim WHERE employee_id = :employeeId",
      nativeQuery = true)
  long countMyClaims(@Param("employeeId") UUID employeeId);

  /**
   * One page of the manager-facing claim list: optional status filter + optional org-unit scope.
   * Ordered SUBMITTED-first (pending work can never fall off the end of a page), then {@code
   * updated_at} DESC within each group. {@code orgUnitIds} follows the {@code
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
           ORDER BY (CASE WHEN c.status = 'SUBMITTED' THEN 0 ELSE 1 END), c.updated_at DESC
           LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<ExpenseClaimSummaryView> findForManager(
      @Param("status") String status,
      @Param("hasUnits") boolean hasUnits,
      @Param("orgUnitIds") List<UUID> orgUnitIds,
      @Param("size") int size,
      @Param("offset") long offset);

  /** The total count backing {@link #findForManager} (the envelope's {@code totalElements}). */
  @Query(
      value =
          """
          SELECT COUNT(*)
            FROM expense_claim c
           WHERE (CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))
             AND (:hasUnits = FALSE OR c.org_unit_id IN (:orgUnitIds))
          """,
      nativeQuery = true)
  long countForManager(
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

  /**
   * Takes a DETERMINISTIC per-tenant transaction-scoped advisory lock (W1, code review) —
   * serializes concurrent attempts to establish a tenant's FIRST expense-claim currency, which is
   * otherwise a check-then-act race: two concurrent first-claims could both observe {@link
   * #findAnyCurrency()} empty and each insert under a DIFFERENT currency, since nothing else
   * constrains it. {@code ExpenseClaimWriter} calls this ONLY on the branch where {@link
   * #findAnyCurrency()} is empty, then RE-PROBES {@link #findAnyCurrency()} under the lock before
   * deciding whether this is genuinely the first claim.
   *
   * <p>Mirrors the finance {@code pg_advisory_xact_lock(hashtext(:key)::bigint)} idiom ({@code
   * PayrollRunLedgerRepository#lockPeriod} et al.): transaction-scoped, auto-released at
   * commit/rollback (no manual unlock), works under the non-superuser {@code app_user} role. The
   * key carries a distinct namespace prefix ({@code "expense_claim_currency:"}) so it can never
   * collide with a differently-namespaced lock keyed on the same {@code company_id} (e.g. a future
   * payroll advisory lock) — {@code hashtext} itself could otherwise collide two DIFFERENT raw
   * strings onto the same 32-bit lock id, which would only over-serialize (never a correctness
   * bug), but the namespace prefix avoids that ambiguity entirely. Returns {@code true} (the void
   * {@code pg_advisory_xact_lock} is mapped to a boolean projection) purely so Spring Data can bind
   * it as a scalar result.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockCurrencyEstablishment(@Param("key") String key);

  /**
   * Locks the claim ROW ({@code FOR UPDATE}) to serialize a receipt replace-swap (E3 review W1):
   * two concurrent uploads to the same claim would otherwise each delete-then-insert under READ
   * COMMITTED and commit two receipt rows. RLS-scoped like every query here; returns the id (or
   * empty for a claim this tenant cannot see — the caller has already 404'd by then).
   */
  @Query(
      value = "SELECT ec.id FROM expense_claim ec WHERE ec.id = :claimId FOR UPDATE",
      nativeQuery = true)
  Optional<UUID> lockForReceiptSwap(@Param("claimId") UUID claimId);
}
