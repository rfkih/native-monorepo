package id.co.nativeapp.org.company;

import java.util.UUID;

/**
 * The application command to create an org unit under the bound company. The tenant scope is
 * already bound at the request edge, so the writer stamps {@code company_id} from {@link
 * id.co.nativeapp.tenant.TenantContext}, never from this command (rule 5).
 *
 * @param name the org-unit name
 * @param type the org-unit type (validated by {@link OrgUnitType})
 * @param parentId the parent org unit, or {@code null} for a top-level node
 */
public record CreateOrgUnitCommand(String name, String type, UUID parentId) {}
