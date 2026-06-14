package id.co.nativeapp.entitlement.entitlement;

/**
 * Thrown when a revoke targets a module the bound tenant was never granted (no {@code
 * tenant_entitlement} row exists for {@code (company_id, module_key)}). The module key is valid (in
 * the catalog) but the resource — this tenant's entitlement for it — does not exist, so the shared
 * advice maps it to a {@code 404} (ENGINEERING-STANDARDS §1.1, "Unknown resource"). The message is
 * English server diagnostics; the frontend maps the stable problem type to an i18n key.
 */
public class EntitlementNotFoundException extends RuntimeException {

  public EntitlementNotFoundException(String moduleKey) {
    super("No entitlement for module '" + moduleKey + "' in the current tenant");
  }
}
