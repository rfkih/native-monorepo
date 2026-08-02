package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * The body for {@code POST /api/v1/me/overtime-entries} — the caller is always resolved from the
 * bound tenant/actor, never from this body (rule 5).
 *
 * @param dayKind {@code "WEEKDAY" | "REST_DAY"}
 */
public record CreateOvertimeEntryRequest(
    @NotNull LocalDate workDate,
    @Positive @Max(600) int minutes,
    @NotNull @Pattern(regexp = "WEEKDAY|REST_DAY") String dayKind) {}
