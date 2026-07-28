package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/employees/{eid}/compensation}. The base pay arrives as
 * integer minor units + ISO-4217 currency (rule 8 — never a float) and is column-encrypted at rest;
 * it is NEVER echoed back (responses are masked, rule 6).
 *
 * @param employmentContractId the employee's contract this package belongs to
 * @param basePayMinor the base pay in minor units (&gt; 0)
 * @param currency the ISO-4217 currency code
 * @param effectiveFrom the package's start
 * @param effectiveTo the package's end, or null for open-ended ({@code 9999-12-31})
 */
public record CreateCompensationRequest(
    @NotNull UUID employmentContractId,
    @NotNull @Positive Long basePayMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotNull LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
