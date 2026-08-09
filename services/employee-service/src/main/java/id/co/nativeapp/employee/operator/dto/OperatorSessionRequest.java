package id.co.nativeapp.employee.operator.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code POST /api/v1/operators/session} (POS surface, ADR 0049) — the
 * employee-pick (+ optional PIN) flow: the till already knows which outlet it is (its own
 * device/session context) and which employee was tapped, and — for a PIN-required outlet — asks the
 * employee to enter their PIN.
 *
 * <p>{@code companyId} and every other tenant/actor value come from {@code TenantContext}, never
 * this body (rule 5) — the caller supplies only WHICH outlet and WHICH employee is attempting to
 * sign in, and the PIN that proves it (when the outlet's policy requires one).
 *
 * <p><strong>{@code pin} carries no FORMAT check here (nullable, no digit pattern) — only a length
 * ceiling ({@code @Size(max = 64)}) to bound the input before it reaches Argon2.</strong> Whether a
 * PIN is required at all depends on the outlet's {@code outlet_operator_policy} (ADR 0049), which
 * is not known until the service layer resolves {@code businessId} — so a missing, blank, or
 * (in-length) malformed {@code pin} is never rejected at this DTO boundary with a distinct {@code
 * 400}. For a PIN-required outlet, {@code OperatorSessionWriter} rejects any such value via the
 * SAME uniform {@code 401 InvalidOperatorPinException} path as a wrong PIN (no format-based oracle,
 * preserving the non-enumeration guarantee); for a no-PIN outlet, the value (if any) is ignored
 * entirely. The length cap is deliberately employee/outlet-INDEPENDENT, so the one {@code 400} it
 * can raise (an over-64-char value) leaks nothing about PIN state — it only stops an
 * unbounded-input cost-amplification against the Argon2 verify (security/code review W1).
 *
 * @param businessId the outlet (== business unit id, ADR 0012) the operator is signing into
 * @param employeeId the employee attempting to sign in (tapped from the till's employee picker)
 * @param pin the PIN the employee entered, or {@code null}/blank when the outlet's policy does not
 *     require one
 */
public record OperatorSessionRequest(
    @NotNull UUID businessId, @NotNull UUID employeeId, @Nullable @Size(max = 64) String pin) {

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
