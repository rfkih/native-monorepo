package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/**
 * The {@code POST /api/v1/payroll-runs} request body. {@code company_id} is NEVER here (stamped
 * from the bound tenant — rule 5); {@code base_currency} is the company's functional currency the
 * dashboard reads from the company (never toggled here).
 *
 * @param period the payroll period, {@code YYYY-MM}
 * @param baseCurrency the run's base ISO-4217 currency (the company's functional currency)
 * @param employeeIds the employees in scope (at least one)
 * @param expectedSourceBusinessIds the business units that must have sealed the period (the gate's
 *     expected set); may be empty if no completeness gate applies
 */
public record RunPayrollRequest(
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
    @NotEmpty List<UUID> employeeIds,
    List<UUID> expectedSourceBusinessIds) {}
