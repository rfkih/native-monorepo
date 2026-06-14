package id.co.nativeapp.carwash.wash;

/**
 * Thrown by {@link WashService} when a company is NOT entitled to the {@code carwash} module and
 * tries to record a wash — the "real gating in the verticals" (Phase-2). It maps to {@code 403
 * Forbidden} via {@link id.co.nativeapp.carwash.config.NotEntitledAdvice} (a tenant/role denial,
 * ENGINEERING-STANDARDS §1.1 status table). No wash row is written and no event is emitted on this
 * path — the gate is checked BEFORE the transactional unit of work.
 */
public class NotEntitledException extends RuntimeException {

  public NotEntitledException(String moduleKey) {
    super("Company is not entitled to the '" + moduleKey + "' module");
  }
}
