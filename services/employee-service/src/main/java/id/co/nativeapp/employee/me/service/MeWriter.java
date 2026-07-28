package id.co.nativeapp.employee.me.service;

import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single WRITE on the {@code /me} path: purge the held one-time login password once the
 * employee has activated their account (ADR 0014).
 *
 * <p>The caller is resolved EXCLUSIVELY from the actor (their JWT {@code sub}), so it can only ever
 * clear the caller's OWN held password. Reaching {@code /me} at all proves Keycloak already forced
 * the change — {@code UPDATE_PASSWORD} blocks token issuance until the employee sets their own — so
 * the first authenticated call is the activation signal. A no-op (no write) when nothing is held. A
 * {@code @Transactional} bean so the Spring proxy + {@code RlsAutoApplyAspect} bind the tenant GUC
 * (the {@code *Writer} pattern, rule 5).
 */
@Component
public class MeWriter {

  private final EmployeeRepository employeeRepository;

  public MeWriter(EmployeeRepository employeeRepository) {
    this.employeeRepository = employeeRepository;
  }

  /**
   * Clears the caller's held one-time login password if present (their first authenticated call).
   */
  @Transactional
  public void purgeTempPasswordForCaller() {
    String actor = TenantContext.require().actor();
    employeeRepository
        .findByUserId(actor)
        .ifPresent(
            employee -> {
              if (employee.clearLoginTempPassword()) {
                employeeRepository.flush();
              }
            });
  }
}
