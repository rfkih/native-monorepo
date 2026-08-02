package id.co.nativeapp.employee.payroll.domain;

/**
 * A THR run (Track P Phase P8, ADR 0035) was requested but the tenant's pay-component catalog is
 * not correctly configured for it — either the {@code THR} earning component itself is not seeded
 * (activate the {@code ID-2026.3} dataset top-up), or {@code BASE}'s {@code run_scope} is still
 * {@code ALL} (would double-pay a full month's base salary ON TOP of the THR earning inside the
 * SAME run — the single highest-stakes THR money-safety guard). Fail-loud, never silently run a THR
 * payroll that could double-pay or omit the THR earning entirely. Mapped to {@code 422} by {@code
 * EmployeeApiAdvice}.
 */
public class ThrRunMisconfiguredException extends RuntimeException {

  public ThrRunMisconfiguredException(String reason) {
    super("THR run misconfigured: " + reason);
  }
}
