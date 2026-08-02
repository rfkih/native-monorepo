package id.co.nativeapp.employee.payroll.dto;

import id.co.nativeapp.employee.payroll.domain.RunType;
import java.time.LocalDate;
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
 * @param runType {@code REGULAR} (default) or {@code THR} (Track P Phase P8, ADR 0035)
 * @param holidayDate the informational H-7 payment date for a THR run (Permenaker 6/2016); {@code
 *     null} for a REGULAR run. NOT persisted (no schema column) and does NOT drive proration —
 *     logged only, for the audit trail.
 */
public record RunPayrollCommand(
    String period,
    List<UUID> employeeIds,
    List<UUID> expectedSourceBusinessIds,
    RunType runType,
    LocalDate holidayDate) {

  /**
   * Backward-compatible constructor defaulting {@code runType} to {@link RunType#REGULAR} and
   * {@code holidayDate} to {@code null} (Track P Phase P8, additive) — every pre-P8 call site keeps
   * compiling/behaving unchanged.
   */
  public RunPayrollCommand(
      String period, List<UUID> employeeIds, List<UUID> expectedSourceBusinessIds) {
    this(period, employeeIds, expectedSourceBusinessIds, RunType.REGULAR, null);
  }
}
