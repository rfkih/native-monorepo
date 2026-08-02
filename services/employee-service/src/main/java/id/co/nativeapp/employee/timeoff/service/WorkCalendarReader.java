package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import id.co.nativeapp.employee.timeoff.dto.WorkCalendarResponse;
import org.springframework.stereotype.Service;

/**
 * The read side for the tenant's {@link WorkCalendar} — seeds the {@code (5, 21)} default on first
 * read (ADR 0033 §6) via {@link WorkCalendarWriter#seedDefaultIfMissing}, so {@code GET
 * /api/v1/work-calendar} never 404s even for a brand-new tenant.
 */
@Service
public class WorkCalendarReader {

  private final WorkCalendarWriter calendarWriter;

  public WorkCalendarReader(WorkCalendarWriter calendarWriter) {
    this.calendarWriter = calendarWriter;
  }

  /** The tenant's work calendar, seeding the default row on first read. */
  public WorkCalendarResponse get() {
    return WorkCalendarResponse.from(calendarWriter.seedDefaultIfMissing());
  }
}
