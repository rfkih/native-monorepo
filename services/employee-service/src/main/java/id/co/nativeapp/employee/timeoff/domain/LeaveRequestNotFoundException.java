package id.co.nativeapp.employee.timeoff.domain;

import java.util.UUID;

/**
 * A leave request referenced by id is unknown, or not visible to the caller (another tenant, or —
 * on the {@code /me} surface — another employee's own request). Mapped to {@code 404} by {@code
 * EmployeeApiAdvice}; the same status for "unknown" and "not yours" is the anti-enumeration idiom.
 */
public class LeaveRequestNotFoundException extends RuntimeException {

  public LeaveRequestNotFoundException(UUID requestId) {
    super("Leave request not found: " + requestId);
  }
}
