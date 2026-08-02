package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/payroll-setup/seed-official} — activates a canned, versioned
 * OFFICIAL statutory dataset (e.g. {@code ID-2026.1}) for the bound tenant. No currency field: the
 * seed inherits the tenant's ALREADY-established payroll currency from an existing statutory rule
 * (illustrative or official) rather than accepting a client-supplied one — {@link
 * id.co.nativeapp.employee.payroll.domain.PayrollSetupNotSeededException} (409) if none exists yet.
 *
 * @param datasetVersion the classpath dataset to activate; {@code 404} if unknown
 */
public record SeedOfficialRequest(@NotBlank String datasetVersion) {}
