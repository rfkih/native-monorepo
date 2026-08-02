package id.co.nativeapp.employee.timeoff.dto;

import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;

/** The tenant's work-calendar row (never the {@code @Entity} itself). */
public record WorkCalendarResponse(int daysPerWeek, int monthlyDivisor) {

  public static WorkCalendarResponse from(WorkCalendar calendar) {
    return new WorkCalendarResponse(calendar.getDaysPerWeek(), calendar.getMonthlyDivisor());
  }
}
