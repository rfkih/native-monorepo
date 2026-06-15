package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * The application command to add a business (org unit) under an existing company. The {@code
 * companyId} comes from the request path; the tenant scope is already bound at the edge for this
 * tenant-scoped endpoint, so the service stamps {@code company_id} from {@link
 * id.co.nativeapp.tenant.TenantContext}, not from this command.
 *
 * @param companyId the owning company (from the path)
 * @param name the org-unit name
 * @param type the org-unit type
 */
public record CreateBusinessCommand(UUID companyId, String name, String type) {}
