package id.co.nativeapp.employee.employee.projection;

import java.util.UUID;

/**
 * Read projection for an employee's effective {@code employment_type} as-of a date — one row per
 * (employee, currently-covering {@code employment_contract}). Used by the payroll run's P0 scope
 * gate (ADR 0055 §5, {@code PayrollRunWriter}) to resolve which employment-type family it must
 * compute PPh 21 against, without loading the full {@code EmploymentContract} aggregate. Lives in
 * the feature's dedicated {@code projection} package (CODE-STRUCTURE §3.3); snake_case native-query
 * aliases map to these accessors.
 */
public interface EmploymentTypeAsOfView {

  UUID getEmployeeId();

  String getEmploymentType();
}
