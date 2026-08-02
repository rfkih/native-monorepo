package id.co.nativeapp.employee.timeoff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the manager-facing tenant-wide overtime-entry list. */
public record OvertimeEntrySummaryResponse(
    UUID id,
    UUID employeeId,
    String employeeName,
    LocalDate workDate,
    int minutes,
    String dayKind,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {}
