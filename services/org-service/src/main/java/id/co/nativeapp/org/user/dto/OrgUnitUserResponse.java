package id.co.nativeapp.org.user.dto;

import java.util.UUID;

/**
 * One row of {@code GET /api/v1/org-units/{orgUnitId}/users}: an active assignment of a user to an
 * outlet under the requested unit. Identity fields (username/email/roles) are deliberately NOT here
 * — the console joins them client-side from its cached {@code GET /api/v1/users} team list, keeping
 * Keycloak out of this read path.
 *
 * @param userId the Keycloak subject the assignment belongs to
 * @param orgUnitId the OUTLET org-unit the assignment targets
 * @param outletName the outlet's display name
 */
public record OrgUnitUserResponse(String userId, UUID orgUnitId, String outletName) {}
