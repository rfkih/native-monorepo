package id.co.nativeapp.employee.employee;

/**
 * An employee's employment status. {@code ACTIVE} employees are currently employed; {@code
 * INACTIVE} covers terminated / off-boarded. Persisted as text ({@code EnumType.STRING}); this enum
 * is the source of truth, and {@link #from(String)} maps an inbound API string to a value,
 * rejecting an unknown one with a {@code 400}.
 */
public enum EmployeeStatus {
  ACTIVE,
  INACTIVE;

  /**
   * Parses a case-insensitive status string, rejecting an unknown value.
   *
   * @throws IllegalArgumentException (→ 400) if {@code raw} is null/blank or not a known status
   */
  public static EmployeeStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("status must not be blank");
    }
    try {
      return EmployeeStatus.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown employee status: " + raw, e);
    }
  }
}
