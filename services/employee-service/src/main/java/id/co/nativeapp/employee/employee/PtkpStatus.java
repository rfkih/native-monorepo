package id.co.nativeapp.employee.employee;

import java.util.Locale;

/**
 * Indonesian PTKP (Penghasilan Tidak Kena Pajak — non-taxable income) status: the tax
 * marital/dependant category that drives PPh 21 withholding. It is a stable HR record attribute on
 * the employee, captured here in the records-only slice; the actual statutory amounts are payroll's
 * concern (deferred). The standard categories combine marital status (TK/K) with the number of
 * dependants (0–3):
 *
 * <ul>
 *   <li>{@code TK0}–{@code TK3} — single (Tidak Kawin) with 0–3 dependants;
 *   <li>{@code K0}–{@code K3} — married (Kawin) with 0–3 dependants.
 * </ul>
 *
 * <p>Persisted as text ({@code EnumType.STRING}); this enum is the source of truth. The enum
 * constants drop the digit-prefix ambiguity by spelling the category, and {@link #from(String)}
 * maps an inbound API string (e.g. {@code "TK0"}) to a value, rejecting an unknown one with a
 * {@code 400}.
 */
public enum PtkpStatus {
  TK0,
  TK1,
  TK2,
  TK3,
  K0,
  K1,
  K2,
  K3;

  /**
   * Parses a case-insensitive PTKP code, rejecting an unknown value.
   *
   * @throws IllegalArgumentException (→ 400) if {@code raw} is null/blank or not a known PTKP
   *     status
   */
  public static PtkpStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("ptkpStatus must not be blank");
    }
    try {
      return PtkpStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown PTKP status: " + raw, e);
    }
  }
}
