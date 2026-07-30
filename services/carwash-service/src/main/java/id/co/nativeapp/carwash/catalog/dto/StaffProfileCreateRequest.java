package id.co.nativeapp.carwash.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body to create a {@code staff_profile} row.
 *
 * @param businessId the carwash outlet (org_unit) this washer profile belongs to
 * @param displayLabel the tenant-entered label (no PII — rule 6)
 * @param employeeId the optional link to the local staff read model; {@code null} for unlinked
 * @param active the optional initial active flag; {@code null} defaults to {@code true}
 */
public record StaffProfileCreateRequest(
    @NotNull UUID businessId, @NotBlank String displayLabel, UUID employeeId, Boolean active) {}
