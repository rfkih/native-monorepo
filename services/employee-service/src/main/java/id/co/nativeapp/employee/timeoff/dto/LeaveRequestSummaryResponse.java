package id.co.nativeapp.employee.timeoff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the manager-facing tenant-wide leave-request list. */
public record LeaveRequestSummaryResponse(
    UUID id,
    UUID employeeId,
    String employeeName,
    String leaveType,
    LocalDate startDate,
    LocalDate endDate,
    int days,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {}
