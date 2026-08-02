package id.co.nativeapp.employee.expense.repository;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.projection.ExpenseClaimSummaryView;
import id.co.nativeapp.employee.expense.projection.LinkedClaimTotalView;
import id.co.nativeapp.employee.expense.projection.MyExpenseClaimView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

  // ---------------------------------------------------------------------
  // ADR 0030 §6 (Phase E5) — the payroll<->expense-claim seam. Called ONLY from {@code
  // ExpenseClaimPayrollLinker} (expense.service), joined to the CALLER's transaction
  // (PayrollRunWriter#calculate / #post, both propagation MANDATORY on the linker side).
  // ---------------------------------------------------------------------

  /**
   * Releases every claim linked to a stale/superseded {@code payroll_run} back to {@code APPROVED}
   * + unlinked, resetting {@code reimbursement_run_id}/{@code settled_at} to {@code NULL} so {@link
   * #linkForRunChunk} can re-link the claim to a corrective run.
   *
   * <p><strong>BINDING (ADR 0030 §6, E4 review W1).</strong> Bumps {@code version} in the SAME
   * statement as every other column: {@code ExpenseClaimWriter#payDirect} flushes a full-column
   * OPTIMISTIC-LOCKED {@code UPDATE ... WHERE id = ? AND version = ?}; if this UPDATE skipped the
   * bump, a stale {@code payDirect} (the entity loaded BEFORE this release ran) would pass its
   * {@code WHERE version =} check and clobber the just-released row — a double payment (direct cash
   * + a later payslip settlement). Bumping it here makes that stale flush lose the optimistic-lock
   * race with {@link org.springframework.orm.ObjectOptimisticLockingFailureException} (→ 409)
   * instead.
   *
   * <p><strong>{@code updated_by} (S1, E5 review).</strong> A native {@code @Modifying} UPDATE
   * bypasses JPA's {@code @LastModifiedBy} auditing listener entirely (it never fires for a
   * statement-level write), so this stamps the literal {@code 'system:payroll-linker'} explicitly —
   * a stable, greppable CDC-audit marker distinguishing this system-driven release from a human
   * actor's edit. No other native {@code @Modifying} UPDATE in this codebase stamped {@code
   * updated_by} before this fix (several {@code INSERT ... ON CONFLICT} upserts thread an explicit
   * caller-supplied actor instead, e.g. {@code MemberBalanceRefRepository}); this establishes the
   * convention for a system-triggered bulk UPDATE with no bound human actor to attribute.
   *
   * <p>Released when the linked run is:
   *
   * <ul>
   *   <li><strong>NOT POSTED — PERIOD-AGNOSTIC (W2, E5 review).</strong> A failed/stale/abandoned
   *       {@code CALCULATED}/{@code FAILED} run never reached POSTED, so no reimbursement it
   *       "carries" ever actually happened. Unlike the other two branches, this one is NOT scoped
   *       to {@code :period}: {@link #linkForRunChunk} links a claim to a run regardless of the
   *       claim's own {@code expense_date} (an outstanding claim rides ANY future run for that
   *       employee), so a claim stranded on an abandoned non-POSTED run of a DIFFERENT period must
   *       be released by ANY later {@code calculate()} — not only a re-run of that SAME stale
   *       period, which an operator has no reason to ever trigger again; or
   *   <li><strong>POSTED, of THIS {@code :period}, and superseded — SAME-CYCLE (W3, E5
   *       review).</strong> Either (a) its {@code run_seq} is STRICTLY LESS THAN the calculating
   *       run's own {@code :currentRunSeq} — every prior POSTED run for a period necessarily has a
   *       lower run_seq than the one about to be assigned ({@code nextRunSeq} is always {@code max
   *       + 1}), so "linked to a POSTED run of this period with a lower run_seq" IS exactly "linked
   *       to a run the CALCULATING run itself supersedes" — the corrective run releases and
   *       re-links the claim in the SAME {@code calculate()} cycle that supersedes it, never a
   *       cycle later — or (b) a STRICTLY HIGHER POSTED {@code run_seq} already exists — kept
   *       alongside (a) as an idempotent overlap safety net (widens, never narrows, the release
   *       condition); or
   *   <li><strong>POSTED (of THIS {@code :period}, the run itself, not superseded) but the claim is
   *       STILL {@code APPROVED}</strong> — the E5-transitional case: {@code markReimbursedAndEmit}
   *       found no {@code EXPENSE_REIMBURSEMENT} payslip line for this run (pre-Track-P-Phase-P7)
   *       and skipped it, leaving the claim linked+APPROVED indefinitely unless a later re-run
   *       releases and re-links it.
   * </ul>
   *
   * <p>A claim already REIMBURSED by the run that is CURRENT (POSTED, {@code run_seq >=
   * currentRunSeq}, not superseded) for this period matches none of the three OR-branches and is
   * left untouched.
   *
   * @param period the period of the run now calculating — scopes branches 2/3 only; branch 1 is
   *     deliberately period-agnostic (W2)
   * @param currentRunSeq the {@code run_seq} about to be assigned to the run now calculating (W3) —
   *     any POSTED run of {@code period} with a lower run_seq is, by construction, superseded by it
   * @return the number of claims released
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE expense_claim c
             SET reimbursement_run_id = NULL,
                 status               = 'APPROVED',
                 settled_at           = NULL,
                 updated_at           = NOW(),
                 updated_by           = 'system:payroll-linker',
                 version              = c.version + 1
            FROM payroll_run pr
           WHERE c.reimbursement_run_id = pr.id
             AND c.status               IN ('APPROVED', 'REIMBURSED')
             AND (
                   pr.status <> 'POSTED'
                   OR (
                        pr.period = :period
                        AND pr.status = 'POSTED'
                        AND (
                              pr.run_seq < :currentRunSeq
                              OR EXISTS (
                                   SELECT 1
                                     FROM payroll_run pr2
                                    WHERE pr2.period  = pr.period
                                      AND pr2.status  = 'POSTED'
                                      AND pr2.run_seq > pr.run_seq
                                 )
                            )
                      )
                   OR (pr.period = :period AND pr.status = 'POSTED' AND c.status = 'APPROVED')
                 )
          """,
      nativeQuery = true)
  int releaseForPeriod(@Param("period") String period, @Param("currentRunSeq") int currentRunSeq);

  /**
   * Chunk-scoped conditional UPDATE (caller chunks {@code employeeIds} at ≤1000 — CLAUDE.md) —
   * links every un-linked {@code APPROVED} + {@code PAYROLL}-method claim among {@code employeeIds}
   * to {@code runId}.
   *
   * <p><strong>BINDING (ADR 0030 §6, same reasoning as {@link #releaseForPeriod}).</strong> Bumps
   * {@code version} in the SAME statement — the version bump is what makes a concurrent {@code
   * payDirect} (which read the claim BEFORE this link landed) lose its optimistic-lock race instead
   * of silently clobbering the link back to {@code NULL}.
   *
   * <p><strong>{@code updated_by} (S1, E5 review).</strong> Stamps the same {@code
   * 'system:payroll-linker'} literal as {@link #releaseForPeriod} — see its Javadoc for why a
   * native {@code @Modifying} UPDATE must set this explicitly (JPA auditing never fires for it).
   *
   * @return the number of claims linked in this chunk
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE expense_claim
             SET reimbursement_run_id = :runId,
                 updated_at           = NOW(),
                 updated_by           = 'system:payroll-linker',
                 version              = version + 1
           WHERE status                = 'APPROVED'
             AND reimbursement_method  = 'PAYROLL'
             AND reimbursement_run_id IS NULL
             AND employee_id IN (:employeeIds)
          """,
      nativeQuery = true)
  int linkForRunChunk(@Param("runId") UUID runId, @Param("employeeIds") List<UUID> employeeIds);

  /**
   * Every claim currently linked to {@code runId} — the WRITE-path read {@code
   * ExpenseClaimPayrollLinker#markReimbursedAndEmit} mutates (flips to REIMBURSED) and saves, so
   * the full entity is loaded (CODE-STRUCTURE §3.3, the write-path exception), not a projection.
   */
  List<ExpenseClaim> findByReimbursementRunId(UUID reimbursementRunId);

  /**
   * One row per (employee, currency) among the claims currently linked to {@code runId} AND STILL
   * {@code APPROVED} (W1, E5 review) — the source Track P Phase P7 reads to build the non-taxable
   * {@code EXPENSE_REIMBURSEMENT} payslip line per employee. The {@code status = 'APPROVED'} guard
   * matters because an already-settled ({@code REIMBURSED}) claim stays linked to its settling run
   * FOREVER (only {@link #releaseForPeriod} ever clears {@code reimbursement_run_id}); without the
   * guard, a claim already paid by this same run's own settlement would fold into a totals read
   * taken after that settlement, double-counting it. GROUP BY {@code amount_currency} as well as
   * {@code employee_id}: v1 claims are single-currency per tenant (see {@code ExpenseClaimWriter}'s
   * currency-establishment Javadoc), so in practice this is exactly one row per employee — but
   * grouping by currency too means a hypothetical future multi-currency tenant surfaces as MULTIPLE
   * rows per employee (one per currency) instead of silently summing across currencies into one
   * corrupted total (never mix currencies in one {@code Money} sum, rule 8).
   */
  @Query(
      value =
          """
          SELECT employee_id       AS employee_id,
                 SUM(amount_minor) AS total_minor,
                 amount_currency   AS currency,
                 COUNT(*)          AS claim_count
            FROM expense_claim
           WHERE reimbursement_run_id = :runId
             AND status = 'APPROVED'
           GROUP BY employee_id, amount_currency
           ORDER BY employee_id
          """,
      nativeQuery = true)
  List<LinkedClaimTotalView> findLinkedClaimTotalsByEmployee(@Param("runId") UUID runId);
}
