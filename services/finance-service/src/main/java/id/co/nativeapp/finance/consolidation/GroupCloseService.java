package id.co.nativeapp.finance.consolidation;

import id.co.nativeapp.finance.group.GroupLeadResolver;
import id.co.nativeapp.finance.group.UnknownGroupException;
import id.co.nativeapp.tenant.TenantContext;
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
 * org-service), and the close is bound via {@link TenantContext#callAsGroup} to {@code (tenant =
 * lead, group = groupId)}, so the auto-RLS aspect sets BOTH GUCs and the conjunction WITH CHECK
 * passes. If the group's {@code GroupDefined} has not been consumed the lead is unknown, so this
 * throws {@link UnknownGroupException} rather than closing under an unknown tenant.
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
   * Runs the close for {@code (groupId, period)} at {@code closeRunSeq}, bound to the group's
   * resolved LEAD company and group scope.
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
    try {
      return TenantContext.callAsGroup(
          lead.toString(),
          groupId.toString(),
          CLOSE_ACTOR,
          () -> writer.close(groupId, period, closeRunSeq));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAsGroup declares checked Exception; writer.close throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to run group consolidation close", e);
    }
  }
}
