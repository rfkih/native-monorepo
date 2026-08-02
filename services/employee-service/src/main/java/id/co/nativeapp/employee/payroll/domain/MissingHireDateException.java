package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * A THR run (Track P Phase P8, ADR 0035) needs an employee's tenure to prorate the THR earning
 * (Permenaker 6/2016: months of service / 12) — {@link Employee#getHireDate()} is null AND no
 * assignment exists to fall back to (the {@code PayrollRunWriter} fallback chain: {@code hire_date}
 * &rarr; earliest {@code assignment.effective_from}). Failing loudly here is deliberate: silently
 * defaulting to zero months would silently DENY a genuinely-tenured employee their statutory THR.
 * Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 */
public class MissingHireDateException extends RuntimeException {

  public MissingHireDateException(UUID employeeId) {
    super(
        "Employee "
            + employeeId
            + " has no hire_date on file and no assignment to fall back to — cannot compute THR"
            + " service-months tenure (Permenaker 6/2016); set the employee's hire_date first");
  }
}
