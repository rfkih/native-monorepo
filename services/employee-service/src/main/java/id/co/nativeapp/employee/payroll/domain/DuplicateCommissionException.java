package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * An OPEN commission on the same metric already exists for the employee — a state conflict (409):
 * two open commissions on the same metric would both apply and double-pay. End the existing one
 * first. Names only ids/keys, never an amount (rule 6).
 */
public class DuplicateCommissionException extends RuntimeException {

  public DuplicateCommissionException(UUID employeeId, String metricKey) {
    super(
        "Employee "
            + employeeId
            + " already has an open commission on '"
            + metricKey
            + "'; end it first");
  }
}
