package id.co.nativeapp.carwash.staff.dto;

import java.util.UUID;

/**
 * A decoded staff event ({@code EmployeeChanged} or {@code AssignmentChanged}) reduced to exactly
 * what the local staff read model needs — the application command the listener hands to {@link
 * id.co.nativeapp.carwash.staff.service.StaffProjectionService}. An immutable record carrying the
 * event id (for idempotency), the owning tenant the consumer binds the write to, the employee id
 * (the projection key), the event kind, and the projected fields.
 *
 * <p>For an {@link Kind#EMPLOYEE EMPLOYEE} event the {@code active} flag is meaningful and {@code
 * orgUnitId} is {@code null}; for an {@link Kind#ASSIGNMENT ASSIGNMENT} event {@code orgUnitId} is
 * present and {@code active} is ignored (an assignment does not restate the employee's status — the
 * writer retains the existing active value, defaulting a never-seen employee to active).
 *
 * @param eventId the event's UUID — the idempotency key
 * @param companyId the tenant the consumer binds the write to (via {@code TenantContext.callAs})
 * @param employeeId the employee (the projection primary key)
 * @param kind whether this came from {@code EmployeeChanged} or {@code AssignmentChanged}
 * @param orgUnitId the assigned org unit (set on an ASSIGNMENT event; {@code null} on EMPLOYEE)
 * @param active the employee's active state (meaningful on an EMPLOYEE event)
 */
public record StaffProjectedEvent(
    UUID eventId, String companyId, UUID employeeId, Kind kind, UUID orgUnitId, boolean active) {

  /** Which source event produced this projected command. */
  public enum Kind {
    EMPLOYEE,
    ASSIGNMENT
  }
}
