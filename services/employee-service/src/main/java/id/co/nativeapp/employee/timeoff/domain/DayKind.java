package id.co.nativeapp.employee.timeoff.domain;

/**
 * The calendar kind of the day an {@link OvertimeEntry} was worked (ADR 0033 §2) — feeds the PP
 * 35/2021 overtime-tier table (already shipped as the {@code OVERTIME_HOURLY} statutory rule in
 * dataset {@code ID-2026.1}) in Track P Phase P7. P6 only records which kind of day it was; it
 * applies no rate.
 */
public enum DayKind {

  /** An ordinary working day — 1.5x the first hour, 2x after (PP 35/2021). */
  WEEKDAY,

  /** A scheduled rest day or public holiday — 2x hours 1-7, 3x hour 8, 4x hours 9-10. */
  REST_DAY
}
