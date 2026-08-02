package id.co.nativeapp.employee.payroll.domain;

import java.util.List;
import java.util.UUID;

/**
 * A payroll run's period carries at least one SUBMITTED (undecided) {@code leave_request} or {@code
 * overtime_entry} for one of the run's employees (Track P Phase P7). Mapped to {@code 409 Conflict}
 * by {@code EmployeeApiAdvice}: pay never silently ignores a request the employee/manager have not
 * yet resolved — a manager must approve or reject every pending entry (or the run is deliberately
 * re-scoped to exclude the affected employee) before the period may compute. The message lists only
 * request/entry ids (UUIDs), never PII (rule 6).
 */
public class PendingWorkEntriesException extends RuntimeException {

  public PendingWorkEntriesException(
      String period, List<UUID> pendingLeaveRequestIds, List<UUID> pendingOvertimeEntryIds) {
    super(
        "Period "
            + period
            + " has "
            + pendingLeaveRequestIds.size()
            + " pending (SUBMITTED) leave request(s) "
            + pendingLeaveRequestIds
            + " and "
            + pendingOvertimeEntryIds.size()
            + " pending (SUBMITTED) overtime entry(ies) "
            + pendingOvertimeEntryIds
            + " — decide every pending request/entry before running payroll for this period");
  }
}
