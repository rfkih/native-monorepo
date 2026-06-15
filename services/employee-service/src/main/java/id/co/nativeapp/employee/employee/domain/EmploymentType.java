package id.co.nativeapp.employee.employee.domain;

import java.util.Locale;

/**
 * The kind of an {@link EmploymentContract}. {@code PERMANENT} (PKWTT — indefinite) vs {@code
 * CONTRACT} (PKWT — fixed-term) are the two principal Indonesian forms; {@code INTERN} and {@code
 * PROBATION} cover the common pre-permanent arrangements. Persisted as text ({@code
 * EnumType.STRING}); this enum is the source of truth, and {@link #from(String)} maps an inbound
 * API string to a value, rejecting an unknown one with a {@code 400}.
 */
public enum EmploymentType {
  PERMANENT,
  CONTRACT,
  INTERN,
  PROBATION;

  /**
   * Parses a case-insensitive employment-type string, rejecting an unknown value.
   *
   * @throws IllegalArgumentException (→ 400) if {@code raw} is null/blank or not a known type
   */
  public static EmploymentType from(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("employmentType must not be blank");
    }
    try {
      return EmploymentType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown employment type: " + raw, e);
    }
  }
}
