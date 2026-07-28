package id.co.nativeapp.employee.employee.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.domain.EmployeeStatus;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.domain.EmploymentType;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.domain.UserAlreadyLinkedException;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.employee.employee.messaging.EmployeeChangedSchema;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.employee.repository.EmploymentContractRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the employee aggregate: creating an employee
 * (emit {@code EmployeeChanged}), updating one (emit {@code EmployeeChanged} on a real change), and
 * adding an employment contract. It is a distinct bean (not private methods on {@link
 * EmployeeService}) so each method is invoked through the Spring proxy and the {@link
 * RlsAutoApplyAspect} sets the tenant GUC (rule 5) — the {@code *Writer} pattern.
 *
 * <p>The aggregate mutation and its outbox row commit in ONE transaction (rule 3, the transactional
 * outbox). The {@code EmployeeChanged} payload carries NO PII (rule 6) — only employee_id /
 * company_id / status.
 */
@Component
public class EmployeeWriter {

  private final EmployeeRepository employeeRepository;
  private final EmploymentContractRepository contractRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public EmployeeWriter(
      EmployeeRepository employeeRepository,
      EmploymentContractRepository contractRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.employeeRepository = employeeRepository;
    this.contractRepository = contractRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Creates an employee under the bound company and emits exactly one {@code EmployeeChanged} —
   * atomically. The PII (nik, bank account) is encrypted at rest by the {@link
   * id.co.nativeapp.employee.config.PiiAttributeConverter} on the save.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Employee create(CreateEmployeeCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    Employee employee =
        new Employee(
            command.fullName(),
            PtkpStatus.from(command.ptkpStatus()),
            command.nik(),
            command.bankAccount());
    employee.setCompanyId(tenant);
    Employee saved = employeeRepository.save(employee);
    employeeRepository.flush();

    emitEmployeeChanged(saved, companyId);
    return saved;
  }

  /**
   * Updates an employee (partial) and emits {@code EmployeeChanged} only if a field actually
   * changed — atomically. An unknown employee id is rejected with a {@code 400} (it is invisible
   * under RLS if cross-tenant, and a genuinely unknown id is an illegal argument).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Employee update(UpdateEmployeeCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    Employee employee =
        employeeRepository
            .findById(command.employeeId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown employee"));

    boolean changed =
        employee.update(
            command.fullName(),
            command.ptkpStatus() == null ? null : PtkpStatus.from(command.ptkpStatus()),
            command.nik(),
            command.bankAccount(),
            command.status() == null ? null : EmployeeStatus.from(command.status()));

    if (changed) {
      employeeRepository.flush();
      emitEmployeeChanged(employee, companyId);
    }
    return employee;
  }

  /**
   * Adds an employment contract to an employee (under the bound company). The employee must exist
   * in the bound tenant (RLS makes a cross-tenant one invisible → 400). No event is emitted for a
   * contract in this slice (contracts are not in the EmployeeChanged contract); the employee's
   * legal employer(s) are derived from their contracts for the assignment invariant.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public EmploymentContract addContract(AddContractCommand command) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Confirm the employee exists in the bound tenant before attaching a contract.
    employeeRepository
        .findById(command.employeeId())
        .orElseThrow(() -> new IllegalArgumentException("Unknown employee"));

    EmploymentContract contract =
        new EmploymentContract(
            command.employeeId(),
            EmploymentType.from(command.employmentType()),
            command.legalEmployerId(),
            command.effectiveFrom(),
            command.effectiveTo());
    contract.setCompanyId(companyId);
    EmploymentContract saved = contractRepository.save(contract);
    contractRepository.flush();
    return saved;
  }

  /**
   * Links an employee to a console login (the Keycloak subject id). Service-local — no event: the
   * link never leaves this service (consumers resolve users via the sub directly). One login maps
   * to at most one employee per company: pre-checked here for a clean 409, with the V7 partial
   * unique index as the concurrency backstop.
   *
   * <p>{@code tempPassword} (nullable) is the one-time login password to HOLD encrypted (ADR 0014)
   * so the owner can hand it to the employee until first sign-in; the call is idempotent on the
   * link, so the reset flow reuses it to replace the held password. Null leaves any held value
   * unchanged.
   *
   * @throws EmployeeNotFoundException unknown/foreign employee (→ 404)
   * @throws UserAlreadyLinkedException the login already belongs to another employee (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Employee linkUser(UUID employeeId, String userId, String tempPassword) {
    TenantContext.require();
    Employee employee =
        employeeRepository
            .findById(employeeId)
            .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

    employeeRepository
        .findByUserId(userId)
        .filter(other -> !other.getId().equals(employeeId))
        .ifPresent(
            other -> {
              throw new UserAlreadyLinkedException(userId, other.getId());
            });

    employee.linkUser(userId);
    if (tempPassword != null && !tempPassword.isBlank()) {
      employee.setLoginTempPassword(tempPassword, clock.instant());
    }
    employeeRepository.flush();
    return employee;
  }

  /**
   * Purges an employee's held one-time login password — the activation hook. Called on the
   * employee's own first authenticated request (they can only reach it AFTER Keycloak forced the
   * change), so the owner-visible value disappears once the employee has taken over their account.
   * A no-op (no write) when nothing is held.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void purgeLoginTempPassword(UUID employeeId) {
    TenantContext.require();
    employeeRepository
        .findById(employeeId)
        .ifPresent(
            employee -> {
              if (employee.clearLoginTempPassword()) {
                employeeRepository.flush();
              }
            });
  }

  /** Removes an employee's console-login link (the Keycloak login itself is untouched). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Employee unlinkUser(UUID employeeId) {
    TenantContext.require();
    Employee employee =
        employeeRepository
            .findById(employeeId)
            .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    employee.unlinkUser();
    employeeRepository.flush();
    return employee;
  }

  private void emitEmployeeChanged(Employee employee, UUID companyId) {
    GenericRecord event = EmployeeChangedSchema.toRecord(employee);
    outboxWriter.write(
        EmployeeChangedSchema.AGGREGATE_TYPE,
        employee.getId().toString(),
        EmployeeChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
  }
}
