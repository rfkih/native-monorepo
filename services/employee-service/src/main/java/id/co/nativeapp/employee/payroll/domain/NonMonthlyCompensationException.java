package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * A compensation package covering the run's period is not {@link PayFrequency#MONTHLY} (the
 * statutory spec's scope: pegawai tetap, MONTHLY only — TER/Art-17 assume a monthly cadence).
 * Defensive: {@link PayFrequency} carries only {@code MONTHLY} today, so this is currently
 * unreachable in practice, but the run must fail loudly rather than silently misprorate the moment
 * a second cadence is ever added. Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 */
public class NonMonthlyCompensationException extends RuntimeException {

  public NonMonthlyCompensationException(UUID employeeId, UUID compensationPackageId) {
    super(
        "Employee "
            + employeeId
            + "'s compensation package "
            + compensationPackageId
            + " is not MONTHLY — this payroll engine supports MONTHLY compensation only");
  }
}
