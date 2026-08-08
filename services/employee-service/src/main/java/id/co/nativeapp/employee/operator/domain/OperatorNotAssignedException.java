package id.co.nativeapp.employee.operator.domain;

import java.util.UUID;

/**
 * An operator-session attempt for an employee who is NOT actively assigned to the given outlet (no
 * current {@code assignment} row with {@code org_unit_id = businessId}, effective as of now).
 * Mapped to {@code 403 Forbidden} by the employee-service advice — this is a checked at every
 * sign-in (not just at PIN-set time) because an assignment can end between the PIN being set and
 * the operator signing in. The message names only ids (UUIDs), never PII (rule 6).
 */
public class OperatorNotAssignedException extends RuntimeException {

  public OperatorNotAssignedException(UUID employeeId, UUID businessId) {
    super("Employee " + employeeId + " is not actively assigned to outlet " + businessId);
  }
}
