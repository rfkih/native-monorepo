package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The body for {@code PATCH /api/v1/leave-balances/{employeeId}?year=} — a manager grant/correction
 * to the employee's annual-leave balance for the given year. Replaces the stored {@code
 * adjustment_days} wholesale (not an increment) — the console always shows the current value first.
 */
public record AdjustLeaveBalanceRequest(@NotNull Integer adjustmentDays) {}
