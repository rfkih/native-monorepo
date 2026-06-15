package id.co.nativeapp.employee.employee.dto;

import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Employment-contract response body — the contract just added. Holds no PII and no Money.
 *
 * @param id the contract id
 * @param employeeId the employee this contract is for
 * @param employmentType the contract kind
 * @param legalEmployerId the legal employer
 * @param effectiveFrom the date the contract becomes effective
 * @param effectiveTo the date it ends ({@code 9999-12-31} when open-ended)
 */
public record ContractResponse(
    UUID id,
    UUID employeeId,
    String employmentType,
    UUID legalEmployerId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  public static ContractResponse from(EmploymentContract contract) {
    return new ContractResponse(
        contract.getId(),
        contract.getEmployeeId(),
        contract.getEmploymentType().name(),
        contract.getLegalEmployerId(),
        contract.getEffectiveFrom(),
        contract.getEffectiveTo());
  }
}
