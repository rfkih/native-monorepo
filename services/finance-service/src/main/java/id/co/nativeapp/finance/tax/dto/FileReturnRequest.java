package id.co.nativeapp.finance.tax.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body to file a PPN return for a period. The period is validated as a {@code YYYY-MM} month
 * (mirrors the AR/AP list + statements period params).
 */
public record FileReturnRequest(
    @NotBlank
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "period must be a valid YYYY-MM month")
        String period) {}
