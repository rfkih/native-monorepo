package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.dto.CompensationResponse;
import id.co.nativeapp.employee.payroll.dto.CreateCompensationRequest;
import id.co.nativeapp.employee.payroll.dto.EndCompensationRequest;
import id.co.nativeapp.employee.payroll.service.CompensationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
 * {@code /api/v1/employees/{employeeId}/compensation} — the console's base-pay surface. Base pay is
 * salary PII (rule 6): the create accepts a plaintext minor amount ONCE (encrypted at rest), and
 * every response — create echo, list, end echo — is MASKED ({@code "***"}); the list read never
 * even selects the ciphertext. Overlapping packages are rejected with {@code 409} (the payroll run
 * SUMS covering packages); the "change salary" flow is end-old + create-new.
 */
@Tag(
    name = "Compensation",
    description =
        "Manage an employee's compensation packages; base pay is encrypted at rest and every"
            + " response is masked")
@RestController
@RequestMapping("/api/v1/employees/{employeeId}/compensation")
public class CompensationController {

  private final CompensationService compensationService;

  public CompensationController(CompensationService compensationService) {
    this.compensationService = compensationService;
  }

  @Operation(
      summary = "List an employee's compensation packages (masked)",
      description =
          "Returns the employee's packages with amountMasked only — the base-pay ciphertext is"
              + " never selected on this path. 404 if the employee is not visible to the bound"
              + " tenant.")
  @GetMapping
  public List<CompensationResponse> list(@PathVariable UUID employeeId) {
    return compensationService.listMasked(employeeId);
  }

  @Operation(
      summary = "Create a compensation package",
      description =
          "Creates an effective-dated package (base pay in integer minor units + ISO-4217"
              + " currency, encrypted at rest). 400 when the contract does not belong to the"
              + " employee; 404 for an unknown employee; 409 when the period overlaps an existing"
              + " package (the run sums covering packages). The response is masked.")
  @PostMapping
  public ResponseEntity<CompensationResponse> create(
      @PathVariable UUID employeeId, @Valid @RequestBody CreateCompensationRequest request) {
    CompensationResponse body =
        compensationService.create(
            employeeId,
            request.employmentContractId(),
            request.basePayMinor(),
            request.currency(),
            request.effectiveFrom(),
            request.effectiveTo());
    return ResponseEntity.created(
            URI.create("/api/v1/employees/" + employeeId + "/compensation/" + body.id()))
        .body(body);
  }

  @Operation(
      summary = "End an open compensation package",
      description =
          "Sets the package's effective_to (an effective-dated close — the change-salary flow is"
              + " end-old + create-new). 409 when the package is not open; 404 when it is not"
              + " visible for that employee. The response is masked.")
  @PatchMapping("/{packageId}")
  public CompensationResponse end(
      @PathVariable UUID employeeId,
      @PathVariable UUID packageId,
      @Valid @RequestBody EndCompensationRequest request) {
    return compensationService.end(employeeId, packageId, request.endOn());
  }
}
