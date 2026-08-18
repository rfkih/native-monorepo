package id.co.nativeapp.finance.gl.repository;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.projection.GlCumulativeTrialBalanceLineView;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.projection.JournalEntrySaleView;
import java.util.List;
import java.util.Optional;
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
  /**
   * Looks up the original SALE {@link JournalEntry} by the producing sale aggregate id (the {@code
   * sale_aggregate_id} column set by {@code RevenuePostingWriter} at posting time). Used by {@code
   * ReversalPostingWriter} to resolve the original SALE entry's id so its lines can be negated for
   * per-leg void/refund unwind (Phase 2 — V18).
   *
   * <p>RLS applies: the GUC set by the aspect on the surrounding {@code @Transactional} scopes the
   * lookup to the current tenant — a void event for tenant A cannot resolve a SALE entry belonging
   * to tenant B. The query returns at most one row because {@code sale_aggregate_id} is logically
   * 1:1 with a SALE entry for a given tenant (a sale produces exactly one journal entry).
   *
   * <p>Returns {@link Optional#empty()} when no matching entry exists (the original SALE was never
   * posted, or the sale predates Phase 2); in that case the reversal writer falls back to the
   * 2-line GROSS template (the same behaviour as Phase 1).
   *
   * <p><strong>Projection only.</strong> Selects only the three columns the reversal writer needs
   * ({@code id}, {@code currency}, {@code uses_illustrative_rules}) — never {@code SELECT *}.
   */
  @Query(
      value =
          """
          SELECT je.id                       AS id,
                 je.currency                 AS currency,
                 je.uses_illustrative_rules  AS uses_illustrative_rules,
                 je.net_revenue_minor        AS net_revenue_minor,
                 je.grand_total_minor        AS grand_total_minor
            FROM journal_entry je
           WHERE je.sale_aggregate_id = :saleAggregateId
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<JournalEntrySaleView> findBySaleAggregateId(
      @Param("saleAggregateId") UUID saleAggregateId);

  /**
   * The id of the {@link JournalEntry} posted from a given {@code source_event_id} — used by the
   * register-close correction (ADR 0064) to resolve the prior variance entry a correction
   * supersedes so its lines can be negated into a balanced contra. {@code source_event_id} is
   * UNIQUE (V13), so this returns at most one row. RLS scopes it to the bound tenant (rule 5 — no
   * manual {@code company_id}). Returns {@link Optional#empty()} when the superseded close posted
   * no variance (it reconciled to zero) or has not yet been consumed. Scalar id, not a {@code
   * SELECT *}.
   */
  @Query(
      value =
          "SELECT je.id FROM journal_entry je WHERE je.source_event_id = :sourceEventId LIMIT 1",
      nativeQuery = true)
  Optional<UUID> findIdBySourceEventId(@Param("sourceEventId") UUID sourceEventId);

  /**
   * Whether the entry {@code id} was posted with illustrative (provisional) provenance — read by
   * the register-close correction (ADR 0064) so a reversal contra MIRRORS the flag of the entry it
   * reverses (the {@code ReversalPostingWriter} / {@code PayrollLiabilityWriter} pattern), rather
   * than re-deriving it from the corrected event. RLS-scoped (rule 5). Scalar boolean, not a {@code
   * SELECT *}.
   */
  @Query(
      value = "SELECT je.uses_illustrative_rules FROM journal_entry je WHERE je.id = :id",
      nativeQuery = true)
  Optional<Boolean> findUsesIllustrativeRulesById(@Param("id") UUID id);

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

  /**
   * CUMULATIVE trial balance: aggregates all GL journal lines where the owning entry's period is
   * {@code <=} {@code :asOfPeriod} (inclusive) — the data required to build a Balance Sheet as of a
   * given period end.
   *
   * <p>Each line carries the same columns as {@link #glTrialBalance} but the sum spans every
   * historical period up to and including the as-of period. A left join to {@code chart_of_account}
   * surfaces unmapped accounts as a NULL {@code account_type} (defence-in-depth; the reader must
   * fail loud on NULL rather than silently misclassifying the account — mirrors {@link
   * id.co.nativeapp.finance.gl.service.GlTrialBalanceReader#assertAllAccountsMapped}).
   *
   * <p><strong>No {@code SELECT *}, projection only.</strong> Returns {@link
   * GlCumulativeTrialBalanceLineView}.
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
           WHERE je.period <= :asOfPeriod
           GROUP BY jl.account_code, coa.account_type, jl.currency
          HAVING SUM(jl.debit_minor) <> 0 OR SUM(jl.credit_minor) <> 0
           ORDER BY jl.account_code
          """,
      nativeQuery = true)
  List<GlCumulativeTrialBalanceLineView> glTrialBalanceAsOf(@Param("asOfPeriod") String asOfPeriod);
}
