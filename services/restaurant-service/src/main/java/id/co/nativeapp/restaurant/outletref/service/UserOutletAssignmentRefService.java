package id.co.nativeapp.restaurant.outletref.service;

import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the application of a {@link UserOutletAssignmentEvent} to the local {@code
 * user_outlet_assignment_ref} read model.
 *
 * <p>This service is NOT {@code @Transactional} itself — the transactional boundary lives on
 * {@link UserOutletAssignmentRefWriter} (the {@code *Writer} pattern). It binds the tenant from the
 * event's {@code company_id} before calling the writer so the auto-RLS aspect sets {@code
 * app.current_tenant} on the writer's transaction (rule 5 — RLS applies to the upsert).
 */
@Service
public class UserOutletAssignmentRefService {

  private final UserOutletAssignmentRefWriter writer;

  public UserOutletAssignmentRefService(UserOutletAssignmentRefWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies the event to {@code user_outlet_assignment_ref}, exactly once per event id.
   *
   * <p>Binds the tenant from the event's {@code company_id} so the writer's RLS-guarded upsert
   * runs in the correct company scope (rule 5 — never trust an inbound header for this).
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  public boolean apply(UserOutletAssignmentEvent event) {
    // Bind the tenant from the event payload (never from an inbound header), so RLS
    // is keyed to the correct company_id for the upsert. The actor is the consumer service name.
    try {
      return TenantContext.callAs(
          event.companyId(),
          "org-assignment-consumer",
          () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // writer.apply() only throws RuntimeException; TenantContext.callAs() declares checked
      // Exception as a generic bound — this catch is unreachable in practice.
      throw new IllegalStateException("Unexpected checked exception from callAs", e);
    }
  }
}
