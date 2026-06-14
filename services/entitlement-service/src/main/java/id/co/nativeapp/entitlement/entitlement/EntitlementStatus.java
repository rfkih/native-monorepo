package id.co.nativeapp.entitlement.entitlement;

/**
 * The status of a {@link TenantEntitlement}: a company is currently {@link #ENTITLED} to a module
 * or its entitlement has been {@link #REVOKED}.
 *
 * <p>A revoke flips the status on the existing row (rather than deleting it), so the {@code
 * (company_id, module_key)} natural key and the grant/revoke history survive across
 * grant→revoke→re-grant cycles, and the {@code EntitlementGranted}/{@code EntitlementRevoked}
 * events always reference a stable aggregate.
 */
public enum EntitlementStatus {
  ENTITLED,
  REVOKED
}
