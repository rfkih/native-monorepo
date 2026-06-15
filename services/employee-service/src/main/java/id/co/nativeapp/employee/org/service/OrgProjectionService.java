package id.co.nativeapp.employee.org.service;

import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed org event to the local org read model.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "employee-org-consumer"} actor (which lands in
 * the Auditable {@code created_by}), then delegates to the proxied {@link OrgProjectionWriter} so
 * the {@code @Transactional} advice and the auto-RLS aspect engage under that tenant. The
 * transactional unit of work — dedupe + projection upsert — lives in the writer (a separate bean so
 * the Spring proxy is not bypassed by self-invocation).
 */
@Service
public class OrgProjectionService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "employee-org-consumer";

  private final OrgProjectionWriter writer;

  public OrgProjectionService(OrgProjectionWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded org event, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean apply(OrgUnitProjectedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply org event", e);
    }
  }
}
