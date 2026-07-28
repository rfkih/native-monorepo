package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * The operation references a compensation package not visible to the caller: an unknown id, another
 * employee's package (the path pair does not match), or — invisible under RLS — another tenant's.
 * All collapse to the same {@code 404} (anti-enumeration). The message names only the package id (a
 * UUID), never an amount (rule 6).
 */
public class CompensationNotFoundException extends RuntimeException {

  public CompensationNotFoundException(UUID packageId) {
    super("Compensation package " + packageId + " not found");
  }
}
