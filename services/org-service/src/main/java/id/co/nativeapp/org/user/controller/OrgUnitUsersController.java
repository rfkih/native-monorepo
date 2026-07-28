package id.co.nativeapp.org.user.controller;

import id.co.nativeapp.org.user.dto.OrgUnitUserResponse;
import id.co.nativeapp.org.user.service.OrgUnitUsersReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/org-units/{orgUnitId}/users} — the active user↔outlet assignments under an org
 * unit, for the org-unit hub's People tab.
 *
 * <p>Routed by the gateway's existing {@code /api/v1/org-units/**} route (owner/manager only).
 * Tenant-scoped: the gateway/{@code DevTenantFilter} binds the tenant before the handler runs; RLS
 * scopes every lookup (rule 5). {@code 200} with an empty list for a valid unit nobody is assigned
 * to; {@code 404} for an unknown/foreign unit (anti-enumeration); {@code 400} for a TEAM.
 */
@Tag(
    name = "Org units",
    description = "Active user-outlet assignments under an org unit (the hub's People tab)")
@RestController
@RequestMapping("/api/v1/org-units")
public class OrgUnitUsersController {

  private final OrgUnitUsersReader orgUnitUsersReader;

  public OrgUnitUsersController(OrgUnitUsersReader orgUnitUsersReader) {
    this.orgUnitUsersReader = orgUnitUsersReader;
  }

  /** Active assignments under the unit (own for an OUTLET; child outlets' for a BUSINESS_UNIT). */
  @Operation(
      summary = "List users assigned under an org unit",
      description =
          "Returns the active user-outlet assignments under the unit: the outlet's own when the"
              + " unit is an OUTLET, or across all child outlets when it is a BUSINESS_UNIT."
              + " Identity fields are joined client-side from the team list. 200 with an empty"
              + " list when nobody is assigned; 404 unknown unit; 400 for a TEAM. RLS-scoped"
              + " (rule 5).")
  @GetMapping("/{orgUnitId}/users")
  public ResponseEntity<List<OrgUnitUserResponse>> usersForUnit(@PathVariable UUID orgUnitId) {
    return ResponseEntity.ok(orgUnitUsersReader.usersForUnit(orgUnitId));
  }
}
