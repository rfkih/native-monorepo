package id.co.nativeapp.finance.consolidation.repository;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationLedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the append-only {@link ConsolidationLedgerEntry} ledger (P3d SEAM 3a).
 *
 * <p>A thin data port: derived/JPQL queries only, no business logic, no {@code Money} arithmetic,
 * no manual {@code WHERE company_id} / {@code WHERE group_id} — tenant AND group scoping come
 * solely from the auto-applied two-GUC conjunction RLS on every {@code @Transactional} method (rule
 * 5). The close binds {@code (tenant = lead, group = group_id)} before any call.
 */
public interface ConsolidationLedgerRepository
    extends JpaRepository<ConsolidationLedgerEntry, UUID> {

  /**
   * Takes a DETERMINISTIC per-{@code (group, period)} transaction-scoped advisory lock that exists
   * REGARDLESS of whether any consolidation row exists yet — the same primitive the labor writer
   * uses to serialize concurrent/interleaved closes (#23). {@code pg_advisory_xact_lock} keys on
   * the {@code (group_id, period)} pair itself, so two parallel close attempts at different {@code
   * close_run_seq}s cannot both run their reversal-then-repost without seeing each other: the later
   * serializes behind the earlier's committed rows. The lock is transaction-scoped (auto-released
   * at commit/rollback, no manual unlock) and works under the non-superuser {@code app_user}.
   * Called at the TOP of the close, BEFORE any consolidation-row read/insert. Returns {@code true}
   * (the void {@code pg_advisory_xact_lock} is mapped to a boolean projection) purely so Spring
   * Data can bind it as a scalar.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockGroupPeriod(@Param("key") String key);

  /**
   * The PRIMARY entries of a specific PRIOR close run for a {@code (group, period)} — the entries a
   * higher {@code close_run_seq} reverses append-only (one REVERSAL contra per PRIMARY). REVERSAL
   * rows are excluded so a re-close never reverses an already-contra row. The explicit {@code
   * group_id} predicate is defense-in-depth atop the two-GUC RLS (rule 5 remains the enforcing
   * layer; this narrows the scan to the one group so a future non-group-bound caller cannot cross
   * groups).
   */
  @Query(
      """
      SELECT e FROM ConsolidationLedgerEntry e
       WHERE e.groupId = :groupId
         AND e.period = :period
         AND e.closeRunSeq = :closeRunSeq
         AND e.postingRole = id.co.nativeapp.finance.consolidation.domain.ConsolidationPostingRole.PRIMARY
      """)
  List<ConsolidationLedgerEntry> findPriorPrimaries(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);

  /**
   * The PRIMARY {@link ConsolidationEntryType#CTA} entries this close run actually posted for a
   * {@code (group, period, closeRunSeq)} — read back from the ledger so the SEAM-3b integrity gate
   * can sum what LANDED in {@code 3950-CTA-TRANSLATION-RESERVE} INDEPENDENTLY of the in-memory CTA
   * it computed, rather than asserting the {@code x + (-x) == 0} tautology. On a validated-balanced
   * input, {@code signed(translated TB) + sum(these CTA amounts)} must be zero. The explicit {@code
   * group_id} predicate is defense-in-depth atop the two-GUC RLS (rule 5 remains the enforcing
   * layer).
   */
  @Query(
      """
      SELECT e FROM ConsolidationLedgerEntry e
       WHERE e.groupId = :groupId
         AND e.period = :period
         AND e.closeRunSeq = :closeRunSeq
         AND e.entryType = id.co.nativeapp.finance.consolidation.domain.ConsolidationEntryType.CTA
         AND e.postingRole = id.co.nativeapp.finance.consolidation.domain.ConsolidationPostingRole.PRIMARY
      """)
  List<ConsolidationLedgerEntry> findCtaPrimaries(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);
}
