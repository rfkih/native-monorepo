package id.co.nativeapp.finance.assets.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request body to acquire (capitalize) a fixed asset (Phase 6). Amounts are integer minor units in
 * {@code currency} — never a float. Depreciation starts the month after {@code acquisitionDate}
 * (the full-month convention, SME-gated); the date must not be in the future (and the writer bounds
 * the year ≥ 1900 — code-review W-2); life is capped at 50 years as a sanity bound.
 */
public record AcquireAssetRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull @PastOrPresent LocalDate acquisitionDate,
    @Min(1) long costMinor,
    @Min(0) long salvageMinor,
    @Min(1) @Max(600) int usefulLifeMonths,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
