package id.co.nativeapp.employee.operator.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/employees/outlet-pin-policy/{businessId}} (owner/manager
 * only, ADR 0049) — sets the per-outlet operator-PIN policy.
 *
 * @param requirePin whether operator sign-in at this outlet must verify a PIN ({@code true},
 *     today's default) or trust the employee-pick alone ({@code false})
 */
public record SetOutletOperatorPolicyRequest(@NotNull Boolean requirePin) {}
