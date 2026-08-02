package id.co.nativeapp.employee.timeoff.domain;

/**
 * A time-off (leave or overtime) transition was attempted from a state that does not allow it (e.g.
 * approving an already-APPROVED request) — the AP-Bill/expense-claim idiom (ADR 0033 §3). Shared by
 * both {@link LeaveRequest} and {@link OvertimeEntry}: the guarded lifecycle ({@link
 * TimeoffStatus}) is identical for both, so one exception type (parameterised by a human-readable
 * resource name) covers both aggregates without duplicating the mapping in {@code
 * EmployeeApiAdvice}. Mapped to HTTP 409 (Conflict).
 */
public class TimeoffStateException extends RuntimeException {

  public TimeoffStateException(String resource, TimeoffStatus current, String attempted) {
    super("Cannot " + attempted + " a " + resource + " in status " + current);
  }
}
