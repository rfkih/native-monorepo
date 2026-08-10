package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * A payroll run's in-scope employee has NO {@code employment_contract} row covering the run's
 * as-of date — never contracted, or a contract whose {@code effective_to} is before this run's
 * period (lapsed) — so the ADR 0055 §5 P0 scope gate ({@code PayrollRunWriter#
 * requireSupportedEmploymentTypes}) cannot resolve an {@link
 * id.co.nativeapp.employee.employee.domain.EmploymentType} for them at all (domain-specialist
 * review, P0 fix). Rejecting the WHOLE run rather than silently falling through and computing that
 * employee as <em>pegawai tetap</em> by DEFAULT is the fail-closed fix: a gate whose entire purpose
 * is to reject an employee it cannot classify must not let an unclassifiable employee slip through
 * for want of a row. Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 */
public class MissingEmploymentContractForRunException extends RuntimeException {

  public MissingEmploymentContractForRunException(UUID employeeId) {
    super(
        "Payroll cannot compute employee "
            + employeeId
            + " — no employment_contract covers this run's period; add or extend their contract"
            + " first");
  }
}
