package id.co.nativeapp.entitlement.entitlement.projection;

import java.time.Instant;

/**
 * Read projection over the {@code tenant_entitlement} row — only the columns an {@link
 * id.co.nativeapp.entitlement.entitlement.dto.EntitlementResponse} needs, never the {@code
 * Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.entitlement.entitlement.repository.TenantEntitlementRepository} ({@code
 * findAllViews}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"), so the list read path fetches a narrow column set instead of {@code SELECT *} of
 * the full {@code Auditable} entity. Lives in its own {@code projection} package — a read model is
 * neither the write-side {@code domain} entity nor a request/response {@code dto}.
 *
 * <p>The {@code status} column is stored as {@code VARCHAR(16)} ({@code ENTITLED}/{@code REVOKED});
 * it is surfaced here as a plain {@code String} (JDK-only projection) and converted to the {@link
 * id.co.nativeapp.entitlement.entitlement.domain.EntitlementStatus} enum at the service-layer
 * mapping boundary in {@code EntitlementService.toResponse(TenantEntitlementView)} (the {@code dto}
 * layer must not depend on this {@code projection} type, so the mapping lives in the service).
 *
 * <p>This projection is used ONLY on the display read path ({@code GET /api/v1/entitlements}). The
 * write path (grant/revoke) still loads the full {@link
 * id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement} entity via {@code
 * findByModuleKey}, which is a derived JpaRepository query (no {@code @Query} annotation) returning
 * the entity for mutation+save.
 */
public interface TenantEntitlementView {

  String getModuleKey();

  /** The raw status string ({@code "ENTITLED"} or {@code "REVOKED"}) from the VARCHAR column. */
  String getStatus();

  Instant getGrantedAt();

  Instant getRevokedAt();
}
