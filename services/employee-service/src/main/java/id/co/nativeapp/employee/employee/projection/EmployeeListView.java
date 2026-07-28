package id.co.nativeapp.employee.employee.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for the console HR list ({@code GET /api/v1/employees}) — one row per (employee ×
 * CURRENT assignment in scope), assignment columns null when the employee has no current assignment
 * (unscoped list only).
 *
 * <p>Deliberately selects <strong>no PII</strong> (rule 6): no NIK, no bank account, and {@code
 * has_compensation} is an EXISTS boolean — the {@code base_pay_enc} ciphertext is never even read
 * on this path. Lives in the feature's {@code projection} package (CODE-STRUCTURE §3.3); snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention.
 */
public interface EmployeeListView {

  UUID getEmployeeId();

  String getFullName();

  String getStatus();

  String getPtkpStatus();

  String getUserId();

  UUID getAssignmentId();

  UUID getOrgUnitId();

  String getRole();

  UUID getReportingTo();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();

  Boolean getHasCompensation();
}
