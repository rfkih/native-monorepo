package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * The application command to add a business (org unit) under an existing company. The {@code
 * companyId} comes from the request path; the tenant scope is already bound at the edge for this
 * tenant-scoped endpoint, so the service stamps {@code company_id} from {@link
 * id.co.nativeapp.tenant.TenantContext}, not from this command.
 *
 * <p>A business is always created as a root {@code business_unit} with a seeded default outlet (ADR
 * 0012) — there is no type choice.
 *
 * @param companyId the owning company (from the path)
 * @param name the org-unit name
 */
public record CreateBusinessCommand(UUID companyId, String name) {}
