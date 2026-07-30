package id.co.nativeapp.barbershop.staff.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The local staff read model row: {@code employee_id -> {org_unit_id, active}}. barbershop-service
 * maintains this projection by CONSUMING {@code EmployeeChanged} / {@code AssignmentChanged} from
 * employee-service (rule 2 — no synchronous call; a locally cached read model), so the vertical
 * knows its staff: who is active (from {@code EmployeeChanged} status) and which org unit they are
 * assigned to (from {@code AssignmentChanged}).
 *
 * <p>NO PII is ever projected here — the events carry only {@code employee_id} / {@code company_id}
 * / {@code status} and the assignment dimensions (rule 6). It is an app-maintained read model (NOT
 * a Debezium-CDC-derived projection), so the rule-4 Auditable + rule-5 RLS guarantees apply
 * uniformly: it extends {@link Auditable} and is under the {@code staff} RLS policy. The consumer
 * writes it inside a {@link id.co.nativeapp.tenant.TenantContext} scope bound to the EVENT's {@code
 * company_id}, so the RLS {@code WITH CHECK} passes.
 */
@Entity
@Table(name = "staff")
public class Staff extends Auditable {

  @Id
  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "org_unit_id")
  private UUID orgUnitId;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected Staff() {
    // for JPA
  }

  /**
   * Creates a staff projection row.
   *
   * @param employeeId the employee (the projection key)
   * @param orgUnitId the org unit the employee is assigned to, or {@code null} if not yet known
   * @param active whether the employee is active
   */
  public Staff(UUID employeeId, UUID orgUnitId, boolean active) {
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.orgUnitId = orgUnitId;
    this.active = active;
  }

  /** Applies the active state from a consumed {@code EmployeeChanged}. */
  public void applyEmployeeChange(boolean newActive) {
    this.active = newActive;
  }

  /** Applies the assigned org unit from a consumed {@code AssignmentChanged}. */
  public void applyAssignment(UUID newOrgUnitId) {
    this.orgUnitId = Objects.requireNonNull(newOrgUnitId, "orgUnitId");
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public boolean isActive() {
    return active;
  }
}
