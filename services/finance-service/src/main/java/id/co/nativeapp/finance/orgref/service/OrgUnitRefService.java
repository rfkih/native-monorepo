package id.co.nativeapp.finance.orgref.service;

import id.co.nativeapp.finance.orgref.messaging.OrgUnitRefEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed org-unit event to the {@code org_unit_ref} local read model.
 *
 * <p><strong>Tenant from the event, not a request.</strong> There is no JWT on the consumer path.
 * This service binds the tenant scope from the event's {@code company_id} via {@link
 * TenantContext#callAs} with the fixed {@link OrgUnitRefWriter#CONSUMER_ACTOR} actor (stamped into
 * the Auditable {@code created_by}), then delegates to the proxied {@link OrgUnitRefWriter} so the
 * {@code @Transactional} advice and the auto-RLS aspect engage under that tenant. The RLS GUC is
 * set inside the transaction boundary (rule 5 — the GUC is {@code @Transactional}-bound per the
 * memory note "RLS GUC is @Transactional-only").
 */
@Service
public class OrgUnitRefService {

  private final OrgUnitRefWriter writer;

  public OrgUnitRefService(OrgUnitRefWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded org-unit event, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean apply(OrgUnitRefEvent event) {
    try {
      return TenantContext.callAs(
          event.companyId(), OrgUnitRefWriter.CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // TenantContext.callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply org-unit ref event", e);
    }
  }
}
