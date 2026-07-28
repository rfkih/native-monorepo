package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * Creating a compensation package whose effective period overlaps an existing package of the SAME
 * employee — rejected with {@code 409}: the payroll run SUMS every covering package, so an overlap
 * would silently double-pay. The "change salary" flow is end-the-old + create-the-new. The message
 * names only ids (UUIDs), never an amount (rule 6).
 */
public class OverlappingCompensationException extends RuntimeException {

  public OverlappingCompensationException(UUID employeeId, UUID existingPackageId) {
    super(
        "Employee "
            + employeeId
            + " already has a compensation package ("
            + existingPackageId
            + ") covering part of that period; end it first — the payroll run sums every covering"
            + " package");
  }
}
