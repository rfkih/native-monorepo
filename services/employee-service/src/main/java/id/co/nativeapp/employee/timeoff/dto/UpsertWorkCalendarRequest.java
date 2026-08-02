package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.NotNull;

/** The body for {@code PUT /api/v1/work-calendar} — a single-row upsert (create or replace). */
public record UpsertWorkCalendarRequest(
    @NotNull Integer daysPerWeek, @NotNull Integer monthlyDivisor) {}
