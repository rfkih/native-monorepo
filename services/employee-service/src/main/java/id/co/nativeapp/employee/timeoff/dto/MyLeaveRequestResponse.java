package id.co.nativeapp.employee.timeoff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the caller's own leave-request list. */
public record MyLeaveRequestResponse(
    UUID id,
    String leaveType,
    LocalDate startDate,
    LocalDate endDate,
    int days,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {}
