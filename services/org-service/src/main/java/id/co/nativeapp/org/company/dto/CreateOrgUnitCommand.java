package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * The application command to create an org unit under the bound company. The tenant scope is
 * already bound at the request edge, so the writer stamps {@code company_id} from {@link
 * id.co.nativeapp.tenant.TenantContext}, never from this command (rule 5).
 *
 * @param name the org-unit name
 * @param type the org-unit type (validated by {@link OrgUnitType})
 * @param parentId the parent org unit, or {@code null} for a top-level node
 * @param vertical the business vertical (lowercase), REQUIRED for a {@code business_unit} and
 *     {@code null} otherwise — the conditional rule is enforced by the {@link OrgUnit} aggregate,
 *     so a business unit created without one still fails loud (400)
 */
public record CreateOrgUnitCommand(String name, String type, UUID parentId, String vertical) {

  /**
   * Convenience for outlet/team creation (the common case), where {@code vertical} is correctly
   * {@code null}. A {@code business_unit} created through this constructor fails in the aggregate —
   * the missing vertical can never default silently into a wrong value.
   */
  public CreateOrgUnitCommand(String name, String type, UUID parentId) {
    this(name, type, parentId, null);
  }
}
