package id.co.nativeapp.entitlement.entitlement.dto;

import id.co.nativeapp.entitlement.entitlement.domain.EntitlementStatus;
import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import java.time.Instant;

/**
 * The {@code /api/v1/entitlements} response shape for one entitlement — a DTO at the boundary,
 * never the {@link TenantEntitlement} entity (no lazy-load / Auditable / {@code company_id} leak;
 * rule 5 / the DTO-at-the-boundary rule). {@code company_id} is intentionally NOT a field: the
 * caller is already the bound tenant, and exposing it would only echo what the JWT proved.
 *
 * @param moduleKey the module key
 * @param status the current status ({@code ENTITLED} / {@code REVOKED})
 * @param grantedAt when the module was (last) granted
 * @param revokedAt when the module was (last) revoked, or {@code null} while entitled
 */
public record EntitlementResponse(
    String moduleKey, EntitlementStatus status, Instant grantedAt, Instant revokedAt) {

  /**
   * Maps a persisted entitlement entity to its response DTO. Used on write-result paths (grant /
   * revoke) where the writer returns the full {@link TenantEntitlement} aggregate.
   */
  public static EntitlementResponse from(TenantEntitlement entitlement) {
    return new EntitlementResponse(
        entitlement.getModuleKey(),
        entitlement.getStatus(),
        entitlement.getGrantedAt(),
        entitlement.getRevokedAt());
  }
}
