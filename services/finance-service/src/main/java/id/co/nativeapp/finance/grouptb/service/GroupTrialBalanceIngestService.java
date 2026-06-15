package id.co.nativeapp.finance.grouptb.service;

import id.co.nativeapp.finance.group.domain.UnknownGroupException;
import id.co.nativeapp.finance.group.service.GroupLeadResolver;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ingesting a consumed {@code TrialBalancePublished} into the group's trial-balance
 * read model (P3d SEAM 2) — resolves the group's LEAD company, binds the two-GUC group scope, and
 * delegates the transactional write to the proxied {@link GroupTrialBalanceWriter}.
 *
 * <p><strong>The inbound tenant is the group's LEAD, resolved from the read model, not a request.
 * </strong> finance is purely downstream; there is no JWT on the consumer path. The {@code
 * TrialBalancePublished} event carries the MEMBER {@code company_id} (a dimension) and the {@code
 * group_id}, but NOT the lead — and the lead is exactly the tenant the {@code group_trial_balance}
 * write is owned by (rule 4). So the lead is resolved from the local {@code group_lead} reference
 * mapping ({@link GroupLeadResolver} — rule 2, never a sync call to org-service), and the handler
 * is bound via {@link TenantContext#callAsGroup} to {@code (tenant = lead, group =
 * event.group_id)}. That is the binding-state-machine's GROUP-WRITE case: the auto-RLS aspect sets
 * BOTH {@code app.current_tenant} (the lead) and {@code app.current_group} (the group), and the
 * conjunction {@code WITH CHECK} on every line passes. Any partial binding (only tenant, only
 * group) would fail closed.
 *
 * <p>If the group's {@code GroupDefined} has not yet been consumed the lead is unknown, so this
 * throws {@link UnknownGroupException} (transient/retryable, NOT a DLT-on-first-attempt poison)
 * rather than ingesting under an unknown tenant — {@code TrialBalancePublished} is partitioned on
 * {@code group_id}, so a retry resolves it once the group is registered.
 */
@Service
public class GroupTrialBalanceIngestService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final GroupTrialBalanceWriter writer;
  private final GroupLeadResolver leadResolver;

  public GroupTrialBalanceIngestService(
      GroupTrialBalanceWriter writer, GroupLeadResolver leadResolver) {
    this.writer = writer;
    this.leadResolver = leadResolver;
  }

  /**
   * Handles one decoded {@code TrialBalancePublished}, idempotently, bound to the group's resolved
   * LEAD company AND the event's group scope.
   *
   * @return {@code true} if this delivery ingested (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(TrialBalancePublishedEvent event) {
    // TRUST ANCHOR (rule 4/5): the tenant is resolved from the org-service-AUTHORITATIVE group_lead
    // mapping (populated ONLY from GroupDefined.lead_company_id), NEVER from any event-carried lead
    // — TrialBalancePublished does not even carry a lead. So a forged/attacker-chosen
    // event.group_id
    // can only ever bind the REAL lead of whatever real group that id names (or defer via
    // UnknownGroupException if no such group is known yet); it can never name an attacker-chosen
    // tenant. event.companyId() (the member dimension) is inert here — it is stamped as
    // member_company_id, never the RLS tenant.
    UUID lead =
        leadResolver
            .leadOf(event.groupId())
            .orElseThrow(
                () ->
                    new UnknownGroupException(
                        "TrialBalancePublished for unknown group "
                            + event.groupId()
                            + " (its GroupDefined has not been consumed yet); retrying"));
    try {
      return TenantContext.callAsGroup(
          lead.toString(), event.groupId().toString(), CONSUMER_ACTOR, () -> writer.ingest(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAsGroup declares checked Exception; writer.ingest throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle TrialBalancePublished", e);
    }
  }
}
