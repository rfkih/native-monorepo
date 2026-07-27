package id.co.nativeapp.org.user.projection;

import java.util.UUID;

/**
 * Slim read projection for the active user ↔ outlet assignments ({@code GET
 * /api/v1/users/{id}/outlets} and {@code GET /api/v1/users/me/outlets}).
 *
 * <p>Selects only the two columns the client needs — the outlet org-unit {@code id} (for the POS
 * picker) and its display {@code name} (joined from {@code org_unit}). Lives in the feature's
 * {@code projection} package (CODE-STRUCTURE §3.3): a read model is neither the write-side {@code
 * domain} entity nor a request/response {@code dto}. Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention.
 */
public interface UserOutletAssignmentView {

  UUID getOrgUnitId();

  String getName();
}
