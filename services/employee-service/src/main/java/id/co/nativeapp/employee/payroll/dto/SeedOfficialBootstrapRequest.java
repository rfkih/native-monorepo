package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/payroll-setup/seed-official-bootstrap} — the go-live default
 * (ADR 0042): seeds a FRESH tenant straight to a fully OFFICIAL statutory setup in one call,
 * skipping the illustrative-placeholder stop. Unlike {@link SeedOfficialRequest}, this endpoint
 * accepts {@code baseCurrency} directly (there is no existing statutory rule yet to inherit a
 * currency from — that is precisely the fresh-tenant case this endpoint exists for).
 *
 * @param baseCurrency the tenant's ISO-4217 base currency (the seeded figures are in it)
 * @param datasetVersion the classpath OFFICIAL dataset to activate; blank/omitted defaults to the
 *     controller's {@code ID-2026.1} default (ADR 0042)
 */
public record SeedOfficialBootstrapRequest(
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency, String datasetVersion) {}
