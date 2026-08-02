package id.co.nativeapp.employee.timeoff.dto;

import java.util.UUID;

/** One row of the manager-facing per-employee derived leave-balance list. */
public record LeaveBalanceRowResponse(
    UUID employeeId,
    String employeeName,
    int year,
    int grantedDays,
    int adjustmentDays,
    int usedDays,
    int remaining) {

  public static LeaveBalanceRowResponse of(
      UUID employeeId,
      String employeeName,
      int year,
      int grantedDays,
      int adjustmentDays,
      int usedDays) {
    return new LeaveBalanceRowResponse(
        employeeId,
        employeeName,
        year,
        grantedDays,
        adjustmentDays,
        usedDays,
        grantedDays + adjustmentDays - usedDays);
  }
}
