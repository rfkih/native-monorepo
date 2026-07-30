package id.co.nativeapp.barbershop.entitlement.service;

import id.co.nativeapp.entitlementcheck.EntitlementLoader;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * barbershop-service's projection-backed {@link EntitlementLoader} — the authoritative fallback the
 * shared {@code libs/entitlement-check} cache consults on a miss.
 *
 * <p>This is the loader the lib's javadoc names for a vertical: "a vertical consuming the lib
 * provides a read-model/projection loader that checks its locally-cached entitlement projection
 * (kept current by EntitlementGranted/Revoked events)". It answers {@code isEntitled(companyId,
 * moduleKey)} by reading this service's own {@code entitlement_projection} via {@link
 * EntitlementProjectionReader}. The read is RLS-scoped, so it binds the passed {@code companyId} as
 * the tenant (via {@link TenantContext#callAs}) before delegating.
 *
 * <p><strong>{@code companyId} is AUTHORITATIVE (rule 5).</strong> It is bound as the tenant for
 * the RLS-scoped read and so decides which company's projection is visible. It MUST come from a
 * trusted source — the already-bound request tenant (the gate runs inside the request scope) or an
 * event's {@code company_id} — and NEVER a client-supplied value. As a fail-fast guard, when a
 * tenant is already bound in {@link TenantContext} this asserts the argument matches it and throws
 * (rather than silently re-binding a different tenant's GUC for the read). When no tenant is bound
 * (an out-of-band cache-seed), the argument is bound as-is.
 */
@Component
public class ProjectionEntitlementLoader implements EntitlementLoader {

  /** The actor bound for any out-of-band (cache-seed) read path. */
  static final String LOADER_ACTOR = "barbershop-entitlement-loader";

  private final EntitlementProjectionReader reader;

  public ProjectionEntitlementLoader(EntitlementProjectionReader reader) {
    this.reader = reader;
  }

  @Override
  public boolean isEntitled(String companyId, String moduleKey) {
    TenantContext.currentCompanyId()
        .filter(bound -> !bound.equals(companyId))
        .ifPresent(
            bound -> {
              throw new IllegalArgumentException(
                  "entitlement-check companyId does not match the bound tenant; refusing to re-bind"
                      + " a different tenant's RLS scope (rule 5)");
            });
    try {
      return TenantContext.callAs(companyId, LOADER_ACTOR, () -> reader.isEntitled(moduleKey));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; the read throws only unchecked, so this is unreachable.
      throw new IllegalStateException("Failed to load entitlement", e);
    }
  }
}
