package id.co.nativeapp.employee.operator.domain;

import java.util.UUID;

/**
 * An operator-session attempt against a PIN that is currently locked (too many recent wrong
 * attempts — {@link OperatorPin#MAX_FAILED_ATTEMPTS}). Mapped to {@code 423 Locked} by the
 * employee-service advice. The message names only the employee id (a UUID), never PII (rule 6).
 */
public class OperatorPinLockedException extends RuntimeException {

  public OperatorPinLockedException(UUID employeeId) {
    super("Operator PIN for employee " + employeeId + " is temporarily locked");
  }
}
