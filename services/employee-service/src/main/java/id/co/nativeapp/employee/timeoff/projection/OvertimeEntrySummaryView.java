package id.co.nativeapp.employee.timeoff.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The manager-facing tenant-wide overtime-entry list row. */
public interface OvertimeEntrySummaryView {

  UUID getId();

  UUID getEmployeeId();

  String getEmployeeName();

  LocalDate getWorkDate();

  int getMinutes();

  String getDayKind();

  String getStatus();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionNote();
}
