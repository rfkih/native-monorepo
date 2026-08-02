package id.co.nativeapp.employee.timeoff.projection;

import java.util.UUID;

/**
 * One APPROVED overtime entry whose {@code work_date} falls in the payroll run's period (Track P
 * Phase P7) — {@code dayKind} ({@code WEEKDAY}/{@code REST_DAY}) selects the PP 35/2021 tier table
 * {@link id.co.nativeapp.employee.payroll.service.WorkInputCalculator#overtimeEarning} applies.
 */
public interface ApprovedOvertimeView {

  UUID getId();

  UUID getEmployeeId();

  int getMinutes();

  String getDayKind();
}
