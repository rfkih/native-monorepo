package id.co.nativeapp.org.user.projection;

import java.util.UUID;

/**
 * One active user↔outlet assignment row under an org unit, for the org-unit hub's People tab
 * ({@code GET /api/v1/org-units/{id}/users}). Snake_case aliases map to these camelCase getters
 * (CLAUDE.md native-query convention). Reachable only from the service + repository layers
 * (projection-layer rule).
 */
public interface OrgUnitUserView {

  /** The Keycloak subject (user id) the assignment belongs to. */
  String getUserId();

  /** The OUTLET org-unit the assignment targets. */
  UUID getOrgUnitId();

  /** The outlet's display name (joined from {@code org_unit}). */
  String getOutletName();
}
