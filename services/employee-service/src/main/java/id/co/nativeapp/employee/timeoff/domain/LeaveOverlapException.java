package id.co.nativeapp.employee.timeoff.domain;

import java.util.UUID;

/**
 * A new leave request's date range overlaps an existing SUBMITTED/APPROVED request for the SAME
 * employee (ADR 0033 §4). Mapped to HTTP 409 (Conflict) — the request is well-formed, but conflicts
 * with the employee's own pending/approved time off. Computed under the per-employee advisory lock
 * (the currency-establishment idiom, {@code LeaveRequestWriter}).
 */
public class LeaveOverlapException extends RuntimeException {

  public LeaveOverlapException(UUID employeeId) {
    super(
        "Leave request overlaps an existing submitted/approved request for employee " + employeeId);
  }
}
