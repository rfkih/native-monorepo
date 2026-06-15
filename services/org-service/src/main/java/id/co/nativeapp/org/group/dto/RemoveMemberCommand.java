package id.co.nativeapp.org.group.dto;

import java.util.UUID;

/**
 * The application command to remove a member company from a consolidation group. Removal CLOSES the
 * membership's effective window (no hard-delete). Both ids come from the request path; the
 * lead/tenant is resolved by the writer from {@link id.co.nativeapp.tenant.TenantContext} (rule 5).
 *
 * @param groupId the group to remove the member from (from the path)
 * @param memberCompanyId the company to remove (from the path)
 */
public record RemoveMemberCommand(UUID groupId, UUID memberCompanyId) {}
