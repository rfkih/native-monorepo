package id.co.nativeapp.finance.assets.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body to create a deferral (Phase 6): a prepaid expense or deferred revenue spread over
 * {@code months} starting at {@code startPeriod} (inclusive). Amounts are integer minor units.
 */
public record CreateDeferralRequest(
    @NotNull @Pattern(
            regexp = "PREPAID_EXPENSE|DEFERRED_REVENUE",
            message = "kind must be PREPAID_EXPENSE or DEFERRED_REVENUE")
        String kind,
    @NotBlank @Size(max = 255) String description,
    @Min(1) long totalMinor,
    @Min(1) @Max(600) int months,
    @NotBlank @Pattern(
            regexp = "\\d{4}-(0[1-9]|1[0-2])",
            message = "startPeriod must be a valid YYYY-MM month")
        String startPeriod,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
