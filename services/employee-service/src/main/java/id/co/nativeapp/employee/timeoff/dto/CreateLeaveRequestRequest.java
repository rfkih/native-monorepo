package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * The body for {@code POST /api/v1/me/leave-requests} — the caller is always resolved from the
 * bound tenant/actor, never from this body (rule 5).
 *
 * @param leaveType {@code "ANNUAL" | "UNPAID" | "SICK"}
 */
public record CreateLeaveRequestRequest(
    @NotNull @Pattern(regexp = "ANNUAL|UNPAID|SICK") String leaveType,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @Positive int days) {}
