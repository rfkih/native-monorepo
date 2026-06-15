package id.co.nativeapp.employee.payroll.dto;

import java.util.List;
import java.util.UUID;

/**
 * Application command to run payroll for a period (design §5). Carries the period, the employees in
 * scope, and the EXPECTED source business_ids that must have sealed the period (the completeness
 * gate's expected set — ARCHITECTURE.md §4). Mapped from {@link RunPayrollRequest} by the
 * controller; {@code company_id} is never on it (stamped from the bound tenant — rule 5).
 *
 * @param period the payroll period (YYYY-MM)
 * @param employeeIds the employees to run (empty = all employees in the tenant is NOT inferred
 *     here; the caller supplies the scope explicitly for determinism)
 * @param expectedSourceBusinessIds the business units that must have sealed the period before the
 *     run may calculate/post (the gate's expected set)
 */
public record RunPayrollCommand(
    String period, List<UUID> employeeIds, List<UUID> expectedSourceBusinessIds) {}
