package id.co.nativeapp.employee.payroll.domain;

/**
 * {@code POST /api/v1/payroll-setup/seed-official} was called for a tenant with NO statutory rule
 * on file yet — the official seed inherits its {@code currency} from an already-established rule
 * (illustrative or official) rather than accepting one on the request, so it needs at least one row
 * to exist first. Mapped to {@code 409 Conflict}: the request is well-formed but the tenant must
 * bootstrap via {@code POST /api/v1/payroll-setup/seed-illustrative} (or an earlier official seed)
 * before activating an official dataset. Unreachable via the console, whose Setup view only renders
 * once the tenant is already seeded — this guards a direct API caller.
 */
public class PayrollSetupNotSeededException extends RuntimeException {

  public PayrollSetupNotSeededException(String tenant) {
    super(
        "Tenant "
            + tenant
            + " has no statutory rule on file yet; seed the illustrative catalog first");
  }
}
