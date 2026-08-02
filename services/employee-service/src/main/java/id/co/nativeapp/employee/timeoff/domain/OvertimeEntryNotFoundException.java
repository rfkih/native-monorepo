package id.co.nativeapp.employee.timeoff.domain;

import java.util.UUID;

/**
 * An overtime entry referenced by id is unknown, or not visible to the caller. Mapped to {@code
 * 404} by {@code EmployeeApiAdvice}; the same status for "unknown" and "not yours" is the
 * anti-enumeration idiom.
 */
public class OvertimeEntryNotFoundException extends RuntimeException {

  public OvertimeEntryNotFoundException(UUID entryId) {
    super("Overtime entry not found: " + entryId);
  }
}
