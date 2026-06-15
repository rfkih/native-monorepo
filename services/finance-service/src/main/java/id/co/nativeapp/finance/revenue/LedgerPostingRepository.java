package id.co.nativeapp.finance.revenue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the append-only {@link LedgerPosting} ledger.
 *
 * <p>A thin data port: derived queries only, no business logic, no {@code Money} arithmetic, no
 * manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS GUC on
 * every {@code @Transactional} method (rule 5). {@link #findBySourceEventId(UUID)} keys on the
 * event UUID (a {@code UNIQUE} column), used as the belt-and-braces idempotency check alongside the
 * {@code ProcessedEventStore}.
 */
public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {

  /** Looks up the posting produced by a given source event (the UNIQUE idempotency key). */
  Optional<LedgerPosting> findBySourceEventId(UUID sourceEventId);

  /**
   * The PRIMARY postings of a payroll run (within the bound tenant), used to REVERSE a superseded
   * prior run — finance posts one contra entry per prior PRIMARY posting (#23). REVERSAL rows are
   * excluded so a re-run never reverses an already-contra row.
   */
  List<LedgerPosting> findByPayrollRunIdAndPostingRole(UUID payrollRunId, PostingRole postingRole);

  /**
   * Aggregates a period's ledger into one trial-balance line per {@code (gl_account_code,
   * posting_type)} (within the bound tenant — RLS adds the {@code company_id} predicate; rule 5),
   * resolving each line's {@code account_type} from {@code chart_of_account} and OR-ing the line's
   * {@code uses_illustrative_rules} across the rolled-up postings (P3d SEAM 4a — the within-company
   * close). The amount is the SIGNED sum of the postings' minor units, so a labor REVERSAL nets a
   * PRIMARY exactly. {@code chart_of_account} is global reference data in the SAME finance database
   * (not another service's — the cross-service-join ban does not apply); the join resolves the
   * account class the trial balance is classified by. A line with zero net amount is excluded (it
   * contributes nothing to the trial balance). Rows are ordered deterministically so the emitted
   * event's lines are reproducible.
   *
   * <p><strong>Defence-in-depth: LEFT JOIN, never INNER (HR-3 — money is never silently
   * dropped).</strong> An {@code INNER JOIN} on {@code chart_of_account} would SILENTLY DROP any
   * posting whose {@code gl_account_code} has no chart row — its money would vanish from the trial
   * balance while the synthesized equity closing line still balanced to the now-understated net.
   * The {@code LEFT JOIN} instead surfaces such a posting as a line with a {@code NULL} {@code
   * account_type}; {@link id.co.nativeapp.finance.withinclose.TrialBalanceReader} then FAILS LOUD
   * on it (the close fails rather than understating the books). The mapping-rule FK prevents an
   * unmapped account today, so this is a guard, not an expected path.
   */
  @Query(
      value =
          """
          SELECT lp.gl_account_code      AS gl_account_code,
                 coa.account_type        AS account_type,
                 lp.posting_type         AS posting_type,
                 SUM(lp.amount_minor)    AS amount_minor,
                 lp.currency             AS currency,
                 bool_or(lp.uses_illustrative_rules) AS uses_illustrative_rules
            FROM ledger_posting lp
            LEFT JOIN chart_of_account coa ON coa.account_code = lp.gl_account_code
           WHERE lp.period = :period
           GROUP BY lp.gl_account_code, coa.account_type, lp.posting_type, lp.currency
          HAVING SUM(lp.amount_minor) <> 0
           ORDER BY lp.gl_account_code, lp.posting_type
          """,
      nativeQuery = true)
  List<TrialBalanceLineProjection> trialBalanceForPeriod(@Param("period") String period);

  /**
   * One aggregated trial-balance line — the native-query projection backing {@link
   * #trialBalanceForPeriod}. Snake_case aliases map to these accessors via the projection-interface
   * convention (CLAUDE.md "native-query aliases snake_case; map via projection interfaces").
   */
  interface TrialBalanceLineProjection {
    String getGlAccountCode();

    String getAccountType();

    String getPostingType();

    long getAmountMinor();

    String getCurrency();

    boolean getUsesIllustrativeRules();
  }
}
