package id.co.nativeapp.employee.timeoff.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code leave_balance} row — the GRANT + any manager ADJUSTMENT for one employee's one
 * calendar year (ADR 0033 §4). This row never stores "days used": usage is DERIVED at read time as
 * the sum of {@code days} across the employee's APPROVED {@link LeaveType#ANNUAL} requests whose
 * {@code start_date} falls in the year — see {@code LeaveBalanceReader}. A row is created lazily
 * (defaults {@code granted_days = 12}, {@code adjustment_days = 0} when absent — the derived read
 * never requires a persisted row) and only actually persisted the first time a manager records a
 * correction via {@code PATCH /api/v1/leave-balances/{employeeId}}.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code leave_balance} RLS policy (rule 5, V12),
 * unique per {@code (company_id, employee_id, year)}.
 */
@Entity
@Table(name = "leave_balance")
public class LeaveBalance extends Auditable {

  /** The statutory default annual grant (UU 13/2003 Art 79) — 12 working days per year. */
  public static final int DEFAULT_GRANTED_DAYS = 12;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "year", nullable = false, updatable = false)
  private int year;

  @Column(name = "granted_days", nullable = false)
  private int grantedDays;

  @Column(name = "adjustment_days", nullable = false)
  private int adjustmentDays;

  protected LeaveBalance() {
    // for JPA
  }

  /**
   * Creates a balance row for {@code (employeeId, year)} with the default grant and no adjustment.
   */
  public LeaveBalance(UUID employeeId, int year) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.year = year;
    this.grantedDays = DEFAULT_GRANTED_DAYS;
    this.adjustmentDays = 0;
  }

  /**
   * Replaces the manager-correction adjustment (may be negative — a correction, not just a grant).
   */
  public void setAdjustmentDays(int adjustmentDays) {
    this.adjustmentDays = adjustmentDays;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public int getYear() {
    return year;
  }

  public int getGrantedDays() {
    return grantedDays;
  }

  public int getAdjustmentDays() {
    return adjustmentDays;
  }

  @Override
  public String toString() {
    return "LeaveBalance[employeeId=" + employeeId + ", year=" + year + "]";
  }
}
