package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.employee.employee.domain.EmploymentType;
import java.util.UUID;

/**
 * A payroll run's in-scope employee resolves to an {@link EmploymentType} the engine cannot yet
 * correctly compute PPh 21 for (ADR 0055 §5, the P0 scope gate). Today's supported set is {@code
 * PERMANENT}/{@code CONTRACT}/{@code PROBATION} — all currently computed as <em>pegawai tetap</em>
 * (monthly TER + December Art-17); {@code INTERN} is deliberately EXCLUDED (domain-specialist
 * review, P0 fix — a magang stipend is usually <em>bukan pegawai</em>, so computing it as pegawai
 * tetap would be a misclassification), and a further type (starting with {@code DAILY_CASUAL})
 * opens only once its own tax path lands. Rejecting the WHOLE run rather than silently computing
 * the unsupported employee with the wrong method is the fix for the latent hole ADR 0031 flagged:
 * {@code GrossToNetCalculator} never read {@code EmploymentType} at all, so pointing a run at an
 * intern, daily/casual, or freelance worker previously produced a wrong-but-plausible PPh 21
 * figure. The message names only the employee id (a UUID) and the employment-type enum value, never
 * PII (rule 6). Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 */
public class UnsupportedEmploymentTypeForRunException extends RuntimeException {

  public UnsupportedEmploymentTypeForRunException(UUID employeeId, EmploymentType employmentType) {
    super(
        "Payroll cannot yet compute employment type "
            + employmentType
            + " for employee "
            + employeeId
            + "; its path is not implemented");
  }
}
