package id.co.nativeapp.employee.me.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PUT /api/v1/me/operator-pin} (ADR 0049 P2) — the employee's own
 * self-service set-or-change of their operator PIN, also serving forgot-PIN (no current PIN is
 * required: the caller is already authenticated on the console self-service surface). The PIN is
 * write-only: it is never echoed back in a response, log, or event (rule 6).
 *
 * @param newPin a 4-to-6-digit numeric PIN
 */
public record SetMyOperatorPinRequest(@NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String newPin) {

  /** Redacts the PIN (rule 6) so an accidental {@code log("{}", request)} can never leak it. */
  @Override
  public String toString() {
    return "SetMyOperatorPinRequest[newPin=***]";
  }
}
