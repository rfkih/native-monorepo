package id.co.nativeapp.finance.consolidation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the group-consolidation total for a (group, period) under the bound two-GUC group scope
 * (P3d SEAM 4b — the read side). A {@code @Transactional(readOnly = true)} bean so the proxy +
 * auto-RLS aspect engage: it carries NO manual {@code WHERE company_id} / {@code WHERE group_id}
 * for tenancy — the two-GUC conjunction RLS (tenant = lead, group = G) constrains every row (rule
 * 5), and the caller ({@link GroupConsolidationService}) has already bound that scope via {@code
 * TenantContext.callAsGroup} AFTER validating the requester leads the group.
 *
 * <p><strong>NO MEMBER-BOOK LEAK (the SEAM-4b invariant).</strong> The DTO is assembled SOLELY from
 * {@code consolidation_summary} (the post-elimination group total + flags) and {@code
 * intercompany_match} aggregate counts (the group's own eliminations status) — NEVER from {@code
 * group_trial_balance}, {@code ledger_posting} or {@code consolidated_pnl} (member-scoped books).
 * The group surface exposes the consolidated/eliminated total + the group eliminations, never a
 * member's standalone trial balance or ledger. (Postgres also fails closed: under the group scope
 * the lead's member-company-id-scoped tables match nothing, so even an accidental member read would
 * return empty.)
 */
@Service
public class GroupConsolidationReader {

  private final ConsolidationSummaryRepository summaryRepository;
  private final IntercompanyMatchRepository matchRepository;

  public GroupConsolidationReader(
      ConsolidationSummaryRepository summaryRepository,
      IntercompanyMatchRepository matchRepository) {
    this.summaryRepository = summaryRepository;
    this.matchRepository = matchRepository;
  }

  /**
   * The active (latest, non-SUPERSEDED) group consolidation for a {@code (group, period)} as a DTO,
   * or empty when no close has been attempted for the period yet. MUST be called inside a {@code
   * callAsGroup(lead, group)} scope.
   *
   * @param groupId the consolidation group (equals the bound group scope)
   * @param period the accounting period {@code YYYY-MM}
   */
  @Transactional(readOnly = true)
  public Optional<GroupConsolidationResponse> read(UUID groupId, String period) {
    return summaryRepository
        .findActiveSummary(groupId, period)
        .map(summary -> GroupConsolidationResponse.from(summary, eliminationStatus(summary)));
  }

  /**
   * The group eliminations / intercompany-match status for the summary's close run — counts ONLY (a
   * total and an unreconciled tally), never a member's leg. Tallied from {@code intercompany_match}
   * under the same bound group scope.
   */
  private GroupEliminationStatusResponse eliminationStatus(ConsolidationSummary summary) {
    UUID groupId = summary.getGroupId();
    String period = summary.getPeriod();
    int seq = summary.getCloseRunSeq();
    long total = matchRepository.countByCloseRun(groupId, period, seq);
    long unreconciled = matchRepository.countUnreconciledByCloseRun(groupId, period, seq);
    return GroupEliminationStatusResponse.of(total, unreconciled);
  }
}
