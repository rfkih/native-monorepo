package id.co.nativeapp.entitlement.entitlement.service;

import id.co.nativeapp.entitlementcheck.EntitlementLoader;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * The owning entitlement-service's DB-backed {@link EntitlementLoader} — the authoritative fallback
 * the shared {@code libs/entitlement-check} cache consults on a miss.
 *
 * <p>It answers {@code isEntitled(companyId, moduleKey)} by reading this service's own {@code
 * tenant_entitlement} table (an {@code ENTITLED} row exists for the pair) via {@link
 * EntitlementReader}. The read is RLS-scoped, so it binds the passed {@code companyId} as the
 * tenant (via {@link TenantContext#callAs}) before delegating — the loader can be called either
 * inside an existing request scope (where the same tenant is already bound) or out of band (when
 * the consumer seeds the cache for a just-granted company), and binding here makes it correct in
 * both. A {@code "entitlement-loader"} actor is used for any audit attribution of the read.
 *
 * <p>This is the loader the lib's javadoc names: "the owning entitlement-service provides the
 * DB-backed loader; a vertical consuming the lib provides a read-model/projection loader."
 */
@Component
public class DbEntitlementLoader implements EntitlementLoader {

  /** The actor bound for the out-of-band (cache-seed) read path. */
  static final String LOADER_ACTOR = "entitlement-loader";

  private final EntitlementReader entitlementReader;

  public DbEntitlementLoader(EntitlementReader entitlementReader) {
    this.entitlementReader = entitlementReader;
  }

  /**
   * {@inheritDoc}
   *
   * <p><strong>{@code companyId} is AUTHORITATIVE (FIX 4 / rule 5).</strong> It is bound as the
   * tenant for the RLS-scoped read and so decides which company's entitlements are visible. It MUST
   * therefore come from a trusted source — a validated JWT {@code company_id} claim (the
   * already-bound request tenant) or an event's {@code company_id} on the consumer seed path — and
   * NEVER a client-supplied value (a request body/header/path), which would let a caller read
   * across tenants.
   *
   * <p>As a fail-fast guard, when a tenant is already bound in {@link TenantContext} this asserts
   * the argument matches it and throws (rather than silently re-binding a different tenant's GUC
   * for the duration of the read). A mismatch means the {@code companyId} flowing into this loader
   * diverged from the request's authenticated tenant — a programming error or an attempt to read
   * another tenant's data — and must fail loudly. When no tenant is bound (the out-of-band
   * cache-seed path), the argument is bound as-is.
   */
  @Override
  public boolean isEntitled(String companyId, String moduleKey) {
    TenantContext.currentCompanyId()
        .filter(bound -> !bound.equals(companyId))
        .ifPresent(
            bound -> {
              throw new IllegalArgumentException(
                  "entitlement-check companyId does not match the bound tenant; refusing to"
                      + " re-bind a different tenant's RLS scope (rule 5)");
            });
    try {
      return TenantContext.callAs(
          companyId, LOADER_ACTOR, () -> entitlementReader.isEntitled(moduleKey));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; the read throws only unchecked, so this is unreachable.
      throw new IllegalStateException("Failed to load entitlement", e);
    }
  }
}
