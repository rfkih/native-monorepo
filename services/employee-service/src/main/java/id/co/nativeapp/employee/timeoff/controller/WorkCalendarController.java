package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import id.co.nativeapp.employee.timeoff.dto.UpsertWorkCalendarRequest;
import id.co.nativeapp.employee.timeoff.dto.WorkCalendarResponse;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarReader;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/work-calendar} — the manager/owner surface (ADR 0033 §6): one row per tenant,
 * seeded with the default {@code (5, 21)} on first read; {@code PUT} upserts it.
 */
@Tag(name = "Work Calendar", description = "Manager surface: the tenant's single work-calendar row")
@RestController
@RequestMapping("/api/v1/work-calendar")
public class WorkCalendarController {

  private final WorkCalendarReader calendarReader;
  private final WorkCalendarWriter calendarWriter;

  public WorkCalendarController(
      WorkCalendarReader calendarReader, WorkCalendarWriter calendarWriter) {
    this.calendarReader = calendarReader;
    this.calendarWriter = calendarWriter;
  }

  @Operation(
      summary =
          "Get the tenant's work calendar (seeds the default 5-day/21-divisor row on first read)")
  @GetMapping
  public WorkCalendarResponse get() {
    return calendarReader.get();
  }

  @Operation(summary = "Upsert the tenant's work calendar")
  @PutMapping
  public WorkCalendarResponse upsert(@Valid @RequestBody UpsertWorkCalendarRequest request) {
    WorkCalendar calendar = calendarWriter.upsert(request.daysPerWeek(), request.monthlyDivisor());
    return WorkCalendarResponse.from(calendar);
  }
}
