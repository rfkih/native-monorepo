package id.co.nativeapp.finance.assets.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request to register a pre-owned (brought-forward) fixed asset during business migration (ADR
 * 0037). Amounts are integer minor units in {@code currency} — never a float. {@code asOfDate} is
 * the go-live date (its month drives the opening entry's period; depreciation starts the month
 * after). {@code openingAccumulatedMinor} is the depreciation already taken (0 for a newly bought
 * asset), bounded by the writer to {@code [0, cost − salvage]}; {@code remainingLifeMonths} is the
 * depreciation still to run (capped at 50 years). Registering posts NO cash — {@code Dr asset cost
 * / Cr accumulated depreciation / Cr opening balance equity}.
 */
public record RegisterBroughtForwardAssetRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull @PastOrPresent LocalDate asOfDate,
    @Min(1) long costMinor,
    @Min(0) long salvageMinor,
    @Min(0) long openingAccumulatedMinor,
    @Min(1) @Max(600) int remainingLifeMonths,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
