package id.co.nativeapp.employee.payroll.projection;

import java.util.UUID;

/**
 * Read projection for a run's payslip index — one row per employee with a payslip in the run,
 * joined to {@code employee} for the display name. Counts only, NO amounts (the amount columns are
 * PII ciphertext and are not selected on this path — rule 6). Snake_case native-query aliases map
 * to these accessors (CODE-STRUCTURE §3.3).
 */
public interface PayslipIndexView {

  UUID getEmployeeId();

  String getFullName();

  Integer getLineCount();

  Boolean getIllustrative();
}
