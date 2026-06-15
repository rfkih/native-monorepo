package id.co.nativeapp.employee.employee.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Creates / updates employees and adds employment contracts for the bound company. It orchestrates
 * the transactional units of work in {@link EmployeeWriter}; the transaction boundary, RLS GUC, and
 * outbox write all live on the writer so the Spring proxy + the auto-RLS aspect engage (the {@code
 * *Writer} pattern, ENGINEERING-STANDARDS §2.5).
 *
 * <p>All endpoints are tenant-scoped (the tenant is bound at the request edge), so this service
 * only asserts a tenant is present and delegates; {@code company_id} is stamped by the writer from
 * {@link TenantContext}, never from the request (rule 5). The PII it passes through (nik, bank
 * account) is never logged here.
 */
@Service
public class EmployeeService {

  private final EmployeeWriter writer;

  public EmployeeService(EmployeeWriter writer) {
    this.writer = writer;
  }

  /** Creates an employee under the bound company and emits {@code EmployeeChanged}. */
  public Employee create(CreateEmployeeCommand command) {
    TenantContext.require();
    return writer.create(command);
  }

  /** Updates an employee (partial) and emits {@code EmployeeChanged} on a real change. */
  public Employee update(UpdateEmployeeCommand command) {
    TenantContext.require();
    return writer.update(command);
  }

  /** Adds an employment contract to an employee under the bound company. */
  public EmploymentContract addContract(AddContractCommand command) {
    TenantContext.require();
    return writer.addContract(command);
  }
}
