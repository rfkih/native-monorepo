package id.co.nativeapp.finance.gl.repository;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the double-entry {@link JournalEntry} aggregate.
 *
 * <p>A thin data port: no business logic, no {@link id.co.nativeapp.money.Money} arithmetic, no
 * manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS GUC on
 * every {@code @Transactional} unit of work (rule 5). {@code source_event_id} carries a {@code
 * UNIQUE} constraint (V13) as the database backstop behind the {@code ProcessedEventStore} dedupe.
 *
 * <p>All {@code @Query} annotations use {@code nativeQuery = true} (CLAUDE.md native-query
 * convention; enforced by the ArchUnit {@code repositoryQueriesAreNative} rule).
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

  /**
   * Aggregates the GL double-entry journal lines for a period into one trial-balance line per
   * {@code account_code} (within the bound tenant — RLS adds the {@code company_id} predicate via
   * the session GUC; rule 5). Each line carries:
   *
   * <ul>
   *   <li>the resolved {@code account_type} from {@code chart_of_account} (LEFT JOIN — a missing
   *       account_type surfaces as NULL, not silently drops the line; defence-in-depth HR-3);
   *   <li>{@code SUM(debit_minor)} and {@code SUM(credit_minor)} (separate columns, not net, so the
   *       trial balance reader can assert Σdebits == Σcredits before computing a net);
   *   <li>{@code bool_or(uses_illustrative_rules)} — the entry-level flag OR-ed down to the line.
   * </ul>
   *
   * <p>Lines where both sums are zero (net-zero) are excluded; they contribute nothing. Rows are
   * ordered deterministically by {@code account_code}.
   *
   * <p><strong>No {@code SELECT *}, projection only.</strong> Returns {@link
   * GlTrialBalanceLineView} — the six columns the reader needs, not the full entity (CLAUDE.md
   * §"Queries are native + projection").
   */
  @Query(
      value =
          """
          SELECT jl.account_code                      AS account_code,
                 coa.account_type                     AS account_type,
                 SUM(jl.debit_minor)                  AS total_debit_minor,
                 SUM(jl.credit_minor)                 AS total_credit_minor,
                 jl.currency                          AS currency,
                 bool_or(je.uses_illustrative_rules)  AS uses_illustrative_rules
            FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.entry_id
            LEFT JOIN chart_of_account coa ON coa.account_code = jl.account_code
           WHERE je.period = :period
           GROUP BY jl.account_code, coa.account_type, jl.currency
          HAVING SUM(jl.debit_minor) <> 0 OR SUM(jl.credit_minor) <> 0
           ORDER BY jl.account_code
          """,
      nativeQuery = true)
  List<GlTrialBalanceLineView> glTrialBalance(@Param("period") String period);
}
