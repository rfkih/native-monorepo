package id.co.nativeapp.employee.timeoff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the caller's own overtime-entry list. */
public record MyOvertimeEntryResponse(
    UUID id,
    LocalDate workDate,
    int minutes,
    String dayKind,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {}
