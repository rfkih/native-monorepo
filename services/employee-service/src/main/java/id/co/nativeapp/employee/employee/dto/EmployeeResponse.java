package id.co.nativeapp.employee.employee.dto;

import id.co.nativeapp.employee.employee.domain.Employee;
import java.util.UUID;

/**
 * Employee response body — the employee just created/updated, or read. <strong>PII is
 * masked</strong> (rule 6): the NIK/NPWP are fully redacted and the bank account is shown only as
 * its last 4 digits; the raw plaintext is NEVER returned to a caller. {@code company_id} is
 * intentionally NOT exposed (it is the bound tenant, implicit in the scope).
 *
 * @param id the employee id
 * @param fullName the person's full name
 * @param ptkpStatus the PTKP tax status
 * @param status the employment status ({@code ACTIVE}/{@code INACTIVE})
 * @param maskedNik the NIK, fully redacted ({@code "***REDACTED***"})
 * @param maskedBankAccount the bank account, masked to its last 4 digits ({@code "****6789"})
 * @param hasNpwp whether an NPWP is on file (drives the payroll engine's no-NPWP surcharge nudge)
 * @param maskedNpwp the NPWP, fully redacted ({@code "***REDACTED***"}), or null when none is on
 *     file
 * @param userId the linked console login's Keycloak subject id (non-PII), or null when unlinked
 */
public record EmployeeResponse(
    UUID id,
    String fullName,
    String ptkpStatus,
    String status,
    String maskedNik,
    String maskedBankAccount,
    boolean hasNpwp,
    String maskedNpwp,
    String userId) {

  /** Builds a masked response from an employee aggregate (never the raw PII — rule 6). */
  public static EmployeeResponse from(Employee employee) {
    return new EmployeeResponse(
        employee.getId(),
        employee.getFullName(),
        employee.getPtkpStatus().name(),
        employee.getStatus().name(),
        employee.maskedNik(),
        employee.maskedBankAccount(),
        employee.hasNpwp(),
        employee.maskedNpwp(),
        employee.getUserId());
  }
}
