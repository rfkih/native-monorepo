package id.co.nativeapp.employee.assignment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The add-assignment request body. Validated at the edge ({@code @Valid}); {@code company_id} /
 * actor come from {@link id.co.nativeapp.tenant.TenantContext} (rule 5), never the body. Holds no
 * PII.
 *
 * <p>The {@code orgUnitId} must already exist in the local org read model (populated by the OrgUnit
 * consumer); its legal employer is resolved from there and checked against the employee's other
 * concurrent assignments (the same-legal-employer invariant).
 *
 * @param orgUnitId the org unit to assign to
 * @param reportingTo the manager (employee id) this assignment reports to, or {@code null}
 * @param role the role held
 * @param effectiveFrom the date the assignment becomes effective
 * @param effectiveTo the date it ends, or {@code null} for open-ended (the 9999-12-31 sentinel)
 */
public record AddAssignmentRequest(
    @NotNull UUID orgUnitId,
    UUID reportingTo,
    @NotBlank @Size(max = 128) String role,
    @NotNull LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
