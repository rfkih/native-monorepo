package id.co.nativeapp.entitlement.entitlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The {@code POST /api/v1/entitlements} request body — grant a module to the bound tenant.
 *
 * <p>An immutable record carrying only the module key; {@code company_id} and actor are NEVER
 * request fields (rule 5) — they come from the bound {@link id.co.nativeapp.tenant.TenantContext}.
 * The {@code @Pattern} pins the key to the catalog's key shape (lowercase alnum + dashes) so a
 * malformed value is a {@code 400} at the edge; the authoritative existence check against {@code
 * module_catalog} happens in the service (an unknown-but-well-formed key is also a {@code 400}).
 *
 * @param moduleKey the module to grant (e.g. {@code "restaurant"}, {@code "finance"})
 */
public record GrantEntitlementRequest(
    @NotBlank @Pattern(
            regexp = "[a-z0-9-]{1,64}",
            message = "moduleKey must be lowercase a-z, 0-9 or '-'")
        String moduleKey) {}
