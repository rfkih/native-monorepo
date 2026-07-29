package id.co.nativeapp.finance.assets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body to run depreciation/amortization for a period (Phase 6). */
public record RunAmortizationRequest(
    @NotBlank
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "period must be a valid YYYY-MM month")
        String period) {}
