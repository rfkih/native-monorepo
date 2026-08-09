package id.co.nativeapp.employee.me.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.operator.service.OperatorPinWriter;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WRITE side of the {@code /me} path: purging the held one-time login password once the
 * employee has activated their account (ADR 0014), and — since ADR 0049 P2 — the employee's own
 * self-service operator-PIN set-or-change.
 *
 * <p>The caller is resolved EXCLUSIVELY from the actor (their JWT {@code sub}), so every write here
 * can only ever touch the caller's OWN row. A {@code @Transactional} bean so the Spring proxy +
 * {@code RlsAutoApplyAspect} bind the tenant GUC (the {@code *Writer} pattern, rule 5).
 */
@Component
public class MeWriter {

  private final EmployeeRepository employeeRepository;
  private final OperatorPinWriter operatorPinWriter;

  public MeWriter(EmployeeRepository employeeRepository, OperatorPinWriter operatorPinWriter) {
    this.employeeRepository = employeeRepository;
    this.operatorPinWriter = operatorPinWriter;
  }

  /**
   * Clears the caller's held one-time login password if present (their first authenticated call).
   * Reaching {@code /me} at all proves Keycloak already forced the change — {@code UPDATE_PASSWORD}
   * blocks token issuance until the employee sets their own — so the first authenticated call is
   * the activation signal. A no-op (no write) when nothing is held.
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

  /**
   * Sets or changes the caller's OWN operator PIN ({@code PUT /api/v1/me/operator-pin}, ADR 0049
   * P2) — the employee-facing counterpart to the owner/manager reset and the till's first-time
   * enrollment. No current PIN is required (the caller is already authenticated on this surface),
   * so this also serves forgot-PIN. Delegates to {@link OperatorPinWriter#setPin} — the SAME
   * create-or-reset upsert the owner/manager path uses — resolving the target strictly from the
   * caller's own login link, never a request parameter (rule 5).
   *
   * @throws EmployeeNotLinkedException the calling login has no employee link (→ 404)
   */
  @Transactional
  public void setOwnOperatorPin(String plaintextPin) {
    String actor = TenantContext.require().actor();
    Employee me =
        employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
    operatorPinWriter.setPin(me.getId(), plaintextPin);
  }
}
