package id.co.nativeapp.finance.group;

import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates projecting org-service group events into finance's local group read model (P3d SEAM
 * 1) — binds the right tenant (the group's LEAD company) and delegates the transactional write to
 * the proxied {@link GroupReadModelWriter}.
 *
 * <p><strong>The inbound tenant comes from the event/read model, not a request.</strong> finance is
 * purely downstream; there is no JWT on the consumer path. For {@code GroupDefined} the lead is on
 * the wire; for {@code GroupMembershipChanged} (which carries no lead) it is resolved from the
 * local {@code group_lead} reference mapping ({@link GroupLeadResolver} — rule 2, never a sync
 * call). The resolved lead is bound via {@link TenantContext#callAs} with a fixed {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), so the writer's
 * {@code @Transactional} advice and the auto-RLS aspect engage under that tenant (rule 5).
 */
@Service
public class GroupReadModelService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final GroupReadModelWriter writer;
  private final GroupLeadResolver leadResolver;

  public GroupReadModelService(GroupReadModelWriter writer, GroupLeadResolver leadResolver) {
    this.writer = writer;
    this.leadResolver = leadResolver;
  }

  /**
   * Handles one decoded {@code GroupDefined}, idempotently, bound to the event's lead company.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handleGroupDefined(GroupDefinedEvent event) {
    try {
      return TenantContext.callAs(
          event.leadCompanyId().toString(), CONSUMER_ACTOR, () -> writer.applyGroupDefined(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle GroupDefined", e);
    }
  }

  /**
   * Handles one decoded {@code GroupMembershipChanged}, idempotently, bound to the group's resolved
   * lead company. If the group's {@code GroupDefined} has not yet been consumed the lead is
   * unknown, so this throws {@link UnknownGroupException} (retryable) rather than projecting under
   * an unknown tenant — events for a group are partitioned on {@code group_id}, so a retry resolves
   * it once the group is registered.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handleMembershipChanged(GroupMembershipChangedEvent event) {
    UUID lead =
        leadResolver
            .leadOf(event.groupId())
            .orElseThrow(
                () ->
                    new UnknownGroupException(
                        "GroupMembershipChanged for unknown group "
                            + event.groupId()
                            + " (its GroupDefined has not been consumed yet); retrying"));
    try {
      return TenantContext.callAs(
          lead.toString(), CONSUMER_ACTOR, () -> writer.applyMembershipChanged(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle GroupMembershipChanged", e);
    }
  }
}
