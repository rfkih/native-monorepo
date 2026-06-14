package id.co.nativeapp.carwash.staff;

import id.co.nativeapp.carwash.staff.StaffProjectedEvent.Kind;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that applies a consumed staff event to the
 * local staff read model — idempotently.
 *
 * <p>It is a distinct bean (not a private method on {@link StaffProjectionService}) so the method
 * is invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC, and
 * the GUC is exactly what makes the RLS {@code WITH CHECK} pass on the projection insert/update
 * (rule 5). The caller binds the tenant from the event's {@code company_id} before invoking this
 * method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the projection upsert
 * happen in ONE transaction: {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the upsert only on the FIRST delivery, so a re-delivered staff event is a clean no-op.
 *
 * <p><strong>EmployeeChanged vs AssignmentChanged.</strong> An {@code EmployeeChanged} sets the
 * employee's {@code active} state (creating the row if unseen, with no org unit yet). An {@code
 * AssignmentChanged} sets the assigned {@code org_unit_id} (creating the row if unseen, defaulting
 * active to true until the {@code EmployeeChanged} replay corrects it). Each kind updates only its
 * own fields, so the two events compose regardless of arrival order.
 */
@Component
public class StaffProjectionWriter {

  private final StaffRepository repository;
  private final ProcessedEventStore processedEvents;

  public StaffProjectionWriter(StaffRepository repository, ProcessedEventStore processedEvents) {
    this.repository = repository;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the event to the staff projection, exactly once per event id. Must be called inside a
   * {@link TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean apply(StaffProjectedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(StaffProjectedEvent event) {
    String tenant = TenantContext.require().companyId();
    Optional<Staff> existing = repository.findById(event.employeeId());

    if (existing.isPresent()) {
      Staff staff = existing.get();
      applyKind(staff, event);
      repository.save(staff);
      return;
    }

    // New staff row. An EmployeeChanged seeds active (org unit unknown until an assignment);
    // an AssignmentChanged seeds the org unit (active defaults true until the EmployeeChanged
    // replay corrects it).
    Staff staff =
        event.kind() == Kind.EMPLOYEE
            ? new Staff(event.employeeId(), null, event.active())
            : new Staff(event.employeeId(), event.orgUnitId(), true);
    staff.setCompanyId(tenant);
    repository.save(staff);
  }

  private static void applyKind(Staff staff, StaffProjectedEvent event) {
    if (event.kind() == Kind.EMPLOYEE) {
      staff.applyEmployeeChange(event.active());
    } else {
      staff.applyAssignment(event.orgUnitId());
    }
  }
}
