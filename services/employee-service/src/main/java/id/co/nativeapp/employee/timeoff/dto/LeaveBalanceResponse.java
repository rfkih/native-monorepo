package id.co.nativeapp.employee.timeoff.dto;

/**
 * The derived leave-balance response — {@code /api/v1/me/leave-balance?year=}. {@code remaining} is
 * {@code grantedDays + adjustmentDays - usedDays} and may be negative (an over-grant correction
 * followed by usage, or a shrunk grant) — the console renders it as-is, never clamped to zero.
 */
public record LeaveBalanceResponse(
    int year, int grantedDays, int adjustmentDays, int usedDays, int remaining) {

  public static LeaveBalanceResponse of(
      int year, int grantedDays, int adjustmentDays, int usedDays) {
    return new LeaveBalanceResponse(
        year, grantedDays, adjustmentDays, usedDays, grantedDays + adjustmentDays - usedDays);
  }
}
