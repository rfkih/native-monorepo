package id.co.nativeapp.employee.operator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/operators/session} (POS surface, ADR 0049 P1) — the
 * employee-pick + PIN flow: the till already knows which outlet it is (its own device/session
 * context) and which employee was tapped, and asks the employee to enter their PIN.
 *
 * <p>{@code companyId} and every other tenant/actor value come from {@code TenantContext}, never
 * this body (rule 5) — the caller supplies only WHICH outlet and WHICH employee is attempting to
 * sign in, and the PIN that proves it.
 *
 * @param businessId the outlet (== business unit id, ADR 0012) the operator is signing into
 * @param employeeId the employee attempting to sign in (tapped from the till's employee picker)
 * @param pin the 4-to-6-digit PIN the employee entered
 */
public record OperatorSessionRequest(
    @NotNull UUID businessId,
    @NotNull UUID employeeId,
    @NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin) {

  /** Redacts the PIN (rule 6) so an accidental {@code log("{}", request)} can never leak it. */
  @Override
  public String toString() {
    return "OperatorSessionRequest[businessId="
        + businessId
        + ", employeeId="
        + employeeId
        + ", pin=***]";
  }
}
