package id.co.nativeapp.employee.employee;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/employees} — manage HR employee records (records-only slice) under the bound
 * company.
 *
 * <ul>
 *   <li>{@code POST /api/v1/employees} — create an employee; returns {@code 201} + {@code
 *       Location}. Emits {@code EmployeeChanged}.
 *   <li>{@code PATCH /api/v1/employees/{employeeId}} — partial update; returns {@code 200}. Emits
 *       {@code EmployeeChanged} on a real change.
 *   <li>{@code POST /api/v1/employees/{employeeId}/contracts} — add an employment contract; returns
 *       {@code 201} + {@code Location}.
 *   <li>{@code GET /api/v1/employees/{employeeId}} — the employee (PII masked) with its
 *       assignments; {@code 200}, or {@code 404} if not visible to the bound tenant.
 * </ul>
 *
 * <p>A thin HTTP adapter: it maps each {@code *Request} to an application {@code *Command}, calls
 * exactly one service method, and maps the result to a DTO — never an entity on the wire
 * (DTO-at-the-boundary). PII is masked in every response (rule 6). The tenant is bound at the edge
 * by the gateway / dev filter, so RLS scopes every lookup and {@code company_id} is stamped from
 * that scope, never the body (rule 5).
 */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

  private final EmployeeService employeeService;
  private final EmployeeReader employeeReader;

  public EmployeeController(EmployeeService employeeService, EmployeeReader employeeReader) {
    this.employeeService = employeeService;
    this.employeeReader = employeeReader;
  }

  /** Create an employee; emits {@code EmployeeChanged}. PII masked in the response. */
  @PostMapping
  public ResponseEntity<EmployeeResponse> createEmployee(
      @Valid @RequestBody CreateEmployeeRequest request) {
    CreateEmployeeCommand command =
        new CreateEmployeeCommand(
            request.fullName(), request.ptkpStatus(), request.nik(), request.bankAccount());
    Employee created = employeeService.create(command);
    EmployeeResponse body = EmployeeResponse.from(created);
    return ResponseEntity.created(URI.create("/api/v1/employees/" + body.id())).body(body);
  }

  /** Partial update of an employee; emits {@code EmployeeChanged} on a real change. */
  @PatchMapping("/{employeeId}")
  public ResponseEntity<EmployeeResponse> updateEmployee(
      @PathVariable UUID employeeId, @Valid @RequestBody UpdateEmployeeRequest request) {
    UpdateEmployeeCommand command =
        new UpdateEmployeeCommand(
            employeeId,
            request.fullName(),
            request.ptkpStatus(),
            request.nik(),
            request.bankAccount(),
            request.status());
    Employee updated = employeeService.update(command);
    return ResponseEntity.ok(EmployeeResponse.from(updated));
  }

  /** Add an employment contract to an employee. */
  @PostMapping("/{employeeId}/contracts")
  public ResponseEntity<ContractResponse> addContract(
      @PathVariable UUID employeeId, @Valid @RequestBody AddContractRequest request) {
    AddContractCommand command =
        new AddContractCommand(
            employeeId,
            request.employmentType(),
            request.legalEmployerId(),
            request.effectiveFrom(),
            request.effectiveTo());
    EmploymentContract contract = employeeService.addContract(command);
    ContractResponse body = ContractResponse.from(contract);
    return ResponseEntity.created(
            URI.create("/api/v1/employees/" + employeeId + "/contracts/" + body.id()))
        .body(body);
  }

  /**
   * Get an employee (PII masked) with its assignments; {@code 404} if not visible to the tenant.
   */
  @GetMapping("/{employeeId}")
  public ResponseEntity<EmployeeWithAssignmentsResponse> getEmployee(
      @PathVariable UUID employeeId) {
    return employeeReader
        .findWithAssignments(employeeId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
