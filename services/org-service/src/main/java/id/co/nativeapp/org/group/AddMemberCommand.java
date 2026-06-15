package id.co.nativeapp.org.group;

import java.util.UUID;

/**
 * The application command to add a member company to a consolidation group. The {@code groupId}
 * comes from the request path; the lead/tenant is resolved by the writer from {@link
 * id.co.nativeapp.tenant.TenantContext}, never from the request (rule 5).
 *
 * @param groupId the group to add the member to (from the path)
 * @param memberCompanyId the company to add
 */
public record AddMemberCommand(UUID groupId, UUID memberCompanyId) {}
