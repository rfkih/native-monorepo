package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.operator.domain.OperatorPin;
import id.co.nativeapp.employee.operator.repository.OperatorPinRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work for owner/manager setting or resetting an employee's
 * operator PIN (ADR 0049 P1) — a distinct bean (not a private method on {@link OperatorPinService})
 * so the Spring proxy + {@link RlsAutoApplyAspect} engage (the {@code *Writer} pattern,
 * CODE-STRUCTURE §3.2).
 *
 * <p>The PIN is Argon2-hashed here and NEVER logged, returned, or placed on the entity's {@code
 * toString()} (rule 6). A reset (an existing row found) clears any lockout state — see {@link
 * OperatorPin#reset}.
 */
@Component
public class OperatorPinWriter {

  private final OperatorPinRepository operatorPinRepository;
  private final EmployeeRepository employeeRepository;
  private final PasswordEncoder operatorPinEncoder;

  public OperatorPinWriter(
      OperatorPinRepository operatorPinRepository,
      EmployeeRepository employeeRepository,
      PasswordEncoder operatorPinEncoder) {
    this.operatorPinRepository = operatorPinRepository;
    this.employeeRepository = employeeRepository;
    this.operatorPinEncoder = operatorPinEncoder;
  }

  /**
   * Sets (first time) or resets (already set) the operator PIN for an employee under the bound
   * company.
   *
   * @throws EmployeeNotFoundException the employee is unknown or not visible in the bound tenant (→
   *     404) — RLS makes another tenant's employee invisible, so this also guards against stamping
   *     an operator_pin row against a phantom cross-tenant employee id (rule 5)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void setPin(UUID employeeId, String plaintextPin) {
    String companyId = TenantContext.require().companyId();

    employeeRepository
        .findById(employeeId)
        .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

    String hash = operatorPinEncoder.encode(plaintextPin);

    OperatorPin operatorPin =
        operatorPinRepository
            .findByEmployeeId(employeeId)
            .map(
                existing -> {
                  existing.reset(hash);
                  return existing;
                })
            .orElseGet(
                () -> {
                  OperatorPin created = new OperatorPin(employeeId, hash);
                  created.setCompanyId(companyId);
                  return created;
                });

    operatorPinRepository.save(operatorPin);
    operatorPinRepository.flush();
  }
}
