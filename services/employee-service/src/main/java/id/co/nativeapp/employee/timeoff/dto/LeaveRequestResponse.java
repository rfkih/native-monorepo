package id.co.nativeapp.employee.timeoff.dto;

import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The full detail response for one leave request (never the {@code @Entity} itself). */
public record LeaveRequestResponse(
    UUID id,
    UUID employeeId,
    String leaveType,
    LocalDate startDate,
    LocalDate endDate,
    int days,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {

  public static LeaveRequestResponse from(LeaveRequest request) {
    return new LeaveRequestResponse(
        request.getId(),
        request.getEmployeeId(),
        request.getLeaveType().name(),
        request.getStartDate(),
        request.getEndDate(),
        request.getDays(),
        request.getStatus().name(),
        request.getDecidedBy(),
        request.getDecidedAt(),
        request.getDecisionNote());
  }
}
