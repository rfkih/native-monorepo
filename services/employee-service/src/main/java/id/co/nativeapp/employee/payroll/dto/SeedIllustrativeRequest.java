package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/payroll-setup/seed-illustrative} — seeds the tenant's
 * default catalog + ILLUSTRATIVE PLACEHOLDER statutory rules in its base currency (idempotent).
 *
 * @param baseCurrency the tenant's ISO-4217 base currency (the seeded figures are in it)
 */
public record SeedIllustrativeRequest(
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency) {}
