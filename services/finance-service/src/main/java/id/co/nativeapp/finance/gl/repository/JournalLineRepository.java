package id.co.nativeapp.finance.gl.repository;

import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the append-only {@link JournalLine} records. Persists lines that have
 * been validated and collected by the {@link JournalLine#debit} / {@link JournalLine#credit}
 * factories and the {@link id.co.nativeapp.finance.gl.domain.JournalEntry#balanced} invariant
 * check.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id}. Tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). Lines are saved as a batch by the writer after the
 * entry header is persisted.
 *
 * <p>All {@code @Query} annotations use {@code nativeQuery = true} (CLAUDE.md native-query
 * convention; enforced by the ArchUnit {@code repositoryQueriesAreNative} rule).
 */
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

  /**
   * Fetches all lines for the given journal entry, ordered by {@code line_no}. Used by {@code
   * ReversalPostingWriter} during per-leg void/refund unwind: the reversal writer reads each
   * original SALE component line (CLEARING debit, GROSS_REVENUE credit, DISCOUNT debit, etc.) and
   * negates it (swap debit ↔ credit) to produce the balanced contra entry (Phase 2 — V18).
   *
   * <p>RLS applies via the GUC set by the surrounding {@code @Transactional}: a void event for
   * tenant A can only read lines belonging to the same tenant's journal entries (rule 5). Returns
   * an empty list when no lines exist for the entry (should not happen for a valid SALE entry, but
   * the reversal writer handles this by falling back to the 2-line GROSS template).
   *
   * <p><strong>Projection only.</strong> Selects only the five columns the reversal writer needs
   * ({@code id}, {@code account_code}, {@code debit_minor}, {@code credit_minor}, {@code currency},
   * {@code line_no}) — never {@code SELECT *} (CLAUDE.md §"native+projection").
   */
  @Query(
      value =
          """
          SELECT jl.id            AS id,
                 jl.account_code  AS account_code,
                 jl.debit_minor   AS debit_minor,
                 jl.credit_minor  AS credit_minor,
                 jl.currency      AS currency,
                 jl.line_no       AS line_no
            FROM journal_line jl
           WHERE jl.entry_id = :entryId
           ORDER BY jl.line_no
          """,
      nativeQuery = true)
  List<JournalLineReversalView> findLinesByEntryId(@Param("entryId") UUID entryId);
}
