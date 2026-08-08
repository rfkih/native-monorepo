package id.co.nativeapp.employee.operator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PUT /api/v1/employees/{employeeId}/operator-pin} (owner/manager only, ADR
 * 0049 P1) — sets or resets the employee's operator PIN. The PIN is write-only: it is never echoed
 * back in a response, log, or event (rule 6).
 *
 * @param pin a 4-to-6-digit numeric PIN
 */
public record SetOperatorPinRequest(@NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin) {

  /** Redacts the PIN (rule 6) so an accidental {@code log("{}", request)} can never leak it. */
  @Override
  public String toString() {
    return "SetOperatorPinRequest[pin=***]";
  }
}
