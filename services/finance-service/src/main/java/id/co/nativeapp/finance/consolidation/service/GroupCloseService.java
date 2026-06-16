package id.co.nativeapp.finance.consolidation.service;

import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.group.domain.UnknownGroupException;
import id.co.nativeapp.finance.group.service.GroupLeadResolver;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a group's consolidation close (P3d SEAM 3a + 3b — MULTI-CURRENCY,
 * FLAGGED-SIMPLIFIED) — resolves the group's LEAD company, binds the two-GUC group scope, and
 * delegates the transactional close to the proxied {@link GroupCloseWriter}. A multi-currency group
 * is now SUPPORTED via the simplified, flagged translation path ({@link
 * SimplifiedTranslationPolicy}); each member book must balance in its own functional currency
 * before it consolidates.
 *
 * <p><strong>This seam exposes the close as a callable SERVICE method, not yet an authz-gated
 * endpoint</strong> (the group authz endpoint + the {@code ConsolidationClosed} producer are SEAM
 * 4). So this is tested directly.
 *
 * <p><strong>Tenant = the group's LEAD, resolved from the local read model.</strong> Every
 * consolidation table is owned by the lead (rule 4) and scoped by the conjunction {@code group_id =
 * current_group AND company_id = current_tenant}. The lead is resolved from the local {@code
 * group_lead} reference mapping ({@link GroupLeadResolver} — rule 2, never a sync call to
 * org-service), and the close is bound via {@link id.co.nativeapp.tenant.TenantContext#callAsGroup}
 * (through {@link GroupScopeRunner}) to {@code (tenant = lead, group = groupId)}, so the auto-RLS
 * aspect sets BOTH GUCs and the conjunction WITH CHECK passes. If the group's {@code GroupDefined}
 * has not been consumed the lead is unknown, so this throws {@link UnknownGroupException} rather
 * than closing under an unknown tenant.
 */
@Service
public class GroupCloseService {

  /** The audit actor for a system-driven close (stamped into {@code created_by}). */
  public static final String CLOSE_ACTOR = "finance-consolidation";

  private final GroupCloseWriter writer;
  private final GroupLeadResolver leadResolver;

  public GroupCloseService(GroupCloseWriter writer, GroupLeadResolver leadResolver) {
    this.writer = writer;
    this.leadResolver = leadResolver;
  }

  /**
   * Runs the close for {@code (groupId, period)} at {@code closeRunSeq}, RESOLVING the group's LEAD
   * company itself (the consumer-driven path, where no lead has been validated upstream). For the
   * request-driven path that has ALREADY validated the lead, prefer {@link #closeAs(UUID, UUID,
   * String, int)} so the bound lead is structurally the validated one (#43).
   *
   * @param groupId the consolidation group
   * @param period the accounting period {@code YYYY-MM}
   * @param closeRunSeq the close run sequence (a higher seq supersedes lower ones)
   * @return the {@link GroupCloseResult}
   */
  public GroupCloseResult close(UUID groupId, String period, int closeRunSeq) {
    UUID lead =
        leadResolver
            .leadOf(groupId)
            .orElseThrow(
                () ->
                    new UnknownGroupException(
                        "Consolidation close for unknown group "
                            + groupId
                            + " (its GroupDefined has not been consumed yet)"));
    return closeAs(lead, groupId, period, closeRunSeq);
  }

  /**
   * Runs the close for {@code (groupId, period)} at {@code closeRunSeq} bound to an
   * ALREADY-VALIDATED {@code lead} — the lead the caller resolved and authorized (#43). Binding the
   * lead passed in makes the bound scope STRUCTURALLY the validated lead rather than a value
   * re-resolved independently inside the close (which could, in principle, diverge from the one the
   * caller validated). The request path ({@link GroupConsolidationService#closeGroup}) uses this;
   * the consumer path uses {@link #close(UUID, String, int)}, which resolves the lead itself.
   *
   * @param lead the group's LEAD company, validated by the caller (the bound tenant)
   * @param groupId the consolidation group
   * @param period the accounting period {@code YYYY-MM}
   * @param closeRunSeq the close run sequence (a higher seq supersedes lower ones)
   * @return the {@link GroupCloseResult}
   */
  public GroupCloseResult closeAs(UUID lead, UUID groupId, String period, int closeRunSeq) {
    return GroupScopeRunner.runInGroupScope(
        lead,
        groupId,
        CLOSE_ACTOR,
        "Failed to run group consolidation close",
        () -> writer.close(groupId, period, closeRunSeq));
  }
}
