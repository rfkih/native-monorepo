package id.co.nativeapp.employee.org.projection;

import java.util.UUID;

/**
 * Read projection over the local {@code org_unit_projection} read model for the console lookup
 * ({@code GET /api/v1/employees/org-units}) — only the columns the console needs to create an
 * employment contract ({@code legal_employer_id}) and label the unit ({@code type}, {@code
 * active}). Lives in the feature's {@code projection} package (CODE-STRUCTURE §3.3); snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention.
 */
public interface OrgUnitView {

  UUID getOrgUnitId();

  UUID getLegalEmployerId();

  String getType();

  Boolean getActive();
}
