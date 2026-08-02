package id.co.nativeapp.employee.timeoff.dto;

import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The full detail response for one overtime entry (never the {@code @Entity} itself). */
public record OvertimeEntryResponse(
    UUID id,
    UUID employeeId,
    LocalDate workDate,
    int minutes,
    String dayKind,
    String status,
    String decidedBy,
    Instant decidedAt,
    String decisionNote) {

  public static OvertimeEntryResponse from(OvertimeEntry entry) {
    return new OvertimeEntryResponse(
        entry.getId(),
        entry.getEmployeeId(),
        entry.getWorkDate(),
        entry.getMinutes(),
        entry.getDayKind().name(),
        entry.getStatus().name(),
        entry.getDecidedBy(),
        entry.getDecidedAt(),
        entry.getDecisionNote());
  }
}
