package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * Ending a compensation package that is no longer open (its {@code effective_to} is already set) —
 * a state conflict, mapped to {@code 409}. The message names only the package id (a UUID), never an
 * amount (rule 6).
 */
public class CompensationAlreadyEndedException extends RuntimeException {

  public CompensationAlreadyEndedException(UUID packageId) {
    super("Compensation package " + packageId + " is not open; only an open package can be ended");
  }
}
