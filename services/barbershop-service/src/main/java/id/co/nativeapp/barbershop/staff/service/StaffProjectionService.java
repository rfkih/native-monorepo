package id.co.nativeapp.barbershop.staff.service;

import id.co.nativeapp.barbershop.staff.dto.StaffProjectedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed staff event to the local staff read model.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "barbershop-staff-consumer"} actor (which lands
 * in the Auditable {@code created_by}), then delegates to the proxied {@link
 * StaffProjectionWriter} so the {@code @Transactional} advice and the auto-RLS aspect engage under
 * that tenant. The transactional unit of work — dedupe + projection upsert — lives in the writer (a
 * separate bean so the Spring proxy is not bypassed by self-invocation).
 */
@Service
public class StaffProjectionService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "barbershop-staff-consumer";

  private final StaffProjectionWriter writer;

  public StaffProjectionService(StaffProjectionWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded staff event, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean apply(StaffProjectedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply staff event", e);
    }
  }
}
