package id.co.nativeapp.employee.timeoff.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One APPROVED overtime entry whose {@code work_date} falls in the payroll run's period (Track P
 * Phase P7) — {@code dayKind} ({@code WEEKDAY}/{@code REST_DAY}) selects the PP 35/2021 tier table
 * {@link id.co.nativeapp.employee.payroll.service.WorkInputCalculator#overtimeEarning} applies.
 *
 * <p>{@code workDate} is REQUIRED (P7 review C1, critical fix): PP 35/2021's multiplier tiers reset
 * PER CALENDAR DAY, not per month — {@code PayrollRunWriter#appendWorkInputs} groups entries by
 * {@code (workDate, dayKind)} and applies one independent tier walk per day, summing the results.
 * Aggregating a WHOLE MONTH's minutes into one tier walk (the pre-fix behaviour) granted the cheap
 * first-tier rate only ONCE for the entire month instead of once per day, materially overpaying
 * (11-44% in the review's worked examples).
 */
public interface ApprovedOvertimeView {

  UUID getId();

  UUID getEmployeeId();

  int getMinutes();

  String getDayKind();

  LocalDate getWorkDate();
}
