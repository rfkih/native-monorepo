package id.co.nativeapp.employee.timeoff.domain;

import java.util.UUID;

/**
 * Approving an ANNUAL leave request would push the employee's used days (already-APPROVED ANNUAL
 * days for the year, plus this request's days) past {@code granted_days + adjustment_days} (ADR
 * 0033 §4). Mapped to HTTP 409 (Conflict). Computed under the per-employee advisory lock, the same
 * lock the overlap guard takes ({@code LeaveRequestWriter}).
 */
public class InsufficientLeaveBalanceException extends RuntimeException {

  public InsufficientLeaveBalanceException(
      UUID employeeId, int year, int requested, int available) {
    super(
        "Approving this "
            + requested
            + "-day request would exceed employee "
            + employeeId
            + "'s "
            + year
            + " leave balance ("
            + available
            + " day(s) remaining)");
  }
}
