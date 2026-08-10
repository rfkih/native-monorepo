package id.co.nativeapp.employee.employee.domain;

import java.util.Locale;

/**
 * The kind of an {@link EmploymentContract}. {@code PERMANENT} (PKWTT — indefinite) vs {@code
 * CONTRACT} (PKWT — fixed-term) are the two principal Indonesian forms; {@code INTERN} and {@code
 * PROBATION} cover the common pre-permanent arrangements; {@code DAILY_CASUAL} (harian lepas /
 * pegawai tidak tetap, ADR 0055) is a daily/shift worker. Persisted as text ({@code
 * EnumType.STRING}); this enum is the source of truth, and {@link #from(String)} maps an inbound
 * API string to a value, rejecting an unknown one with a {@code 400}.
 *
 * <p><strong>Payroll-run scope (ADR 0055 §5).</strong> Storing a value here does NOT mean the
 * payroll engine can correctly compute it yet — {@code PayrollRunWriter} rejects (422) a run that
 * includes an employee whose effective type is not in its currently-supported set. {@code
 * DAILY_CASUAL} is deliberately gated off (its TER-Harian tax path is a later increment); it exists
 * here only so the value can be assigned/stored ahead of that path landing.
 */
public enum EmploymentType {
  PERMANENT,
  CONTRACT,
  INTERN,
  PROBATION,
  DAILY_CASUAL;

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
