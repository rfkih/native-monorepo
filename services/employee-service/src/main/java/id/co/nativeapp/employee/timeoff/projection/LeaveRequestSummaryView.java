package id.co.nativeapp.employee.timeoff.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The manager-facing tenant-wide leave-request list row. */
public interface LeaveRequestSummaryView {

  UUID getId();

  UUID getEmployeeId();

  String getEmployeeName();

  String getLeaveType();

  LocalDate getStartDate();

  LocalDate getEndDate();

  int getDays();

  String getStatus();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionNote();
}
