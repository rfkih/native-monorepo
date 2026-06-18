package id.co.nativeapp.entitlement.entitlement.repository;

import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import id.co.nativeapp.entitlement.entitlement.projection.TenantEntitlementView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link TenantEntitlement} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). Native
 * queries run on the same RLS-scoped connection, so projecting columns by hand does NOT weaken
 * tenant isolation.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> {@link #findAllViews()} fetches
 * only the four business columns the display path needs ({@link TenantEntitlementView}) rather than
 * {@code SELECT *} of the full {@code Auditable} row — narrower I/O, and the entity is loaded only
 * on the write path.
 *
 * <p><strong>Write-path load kept on the entity path.</strong> {@link #findByModuleKey(String)} is
 * a derived query (no {@code @Query} annotation) that returns the full {@link TenantEntitlement}
 * entity. It is intentionally NOT projection-ized: the grant and revoke flows load the entity to
 * mutate it ({@code existing.grant(grantedAt)} / {@code existing.revoke(revokedAt)}) and then
 * {@link org.springframework.data.jpa.repository.JpaRepository#save save} it back, so the full
 * aggregate is required. Keeping it as a derived query also removes the need for a {@code @Query}
 * annotation on the write-load path (rule 3 of the native-query convention applies only to
 * {@code @Query}-annotated methods).
 */
public interface TenantEntitlementRepository extends JpaRepository<TenantEntitlement, UUID> {

  /**
   * The entitlement row for a module within the bound tenant (RLS-scoped), if any. There is at most
   * one per {@code (company_id, module_key)} (the unique constraint), so this is a point lookup.
   *
   * <p><strong>Write-path entity load — deliberately NOT a projection.</strong> The grant and
   * revoke flows mutate this entity ({@code existing.grant(grantedAt)} / {@code
   * existing.revoke(...)}) and save it back; only the full {@link TenantEntitlement} aggregate
   * carries the invariants and the {@link id.co.nativeapp.tenant.Auditable Auditable} columns the
   * JPA save lifecycle needs. No {@code @Query} annotation is present, so the native-query ArchUnit
   * rule does not apply.
   */
  Optional<TenantEntitlement> findByModuleKey(@Param("moduleKey") String moduleKey);

  /**
   * Every entitlement row visible to the bound tenant, projected to {@link TenantEntitlementView}
   * and ordered deterministically. No {@code WHERE company_id} — the result set is constrained
   * solely by the auto-applied RLS policy (rule 5).
   *
   * <p>Used on the pure display read path ({@code GET /api/v1/entitlements}); the entity is never
   * loaded, never mutated, never saved. The four selected columns are exactly what an {@link
   * id.co.nativeapp.entitlement.entitlement.dto.EntitlementResponse} needs.
   */
  @Query(
      value =
          """
          SELECT te.module_key  AS module_key,
                 te.status      AS status,
                 te.granted_at  AS granted_at,
                 te.revoked_at  AS revoked_at
            FROM tenant_entitlement te
           ORDER BY te.granted_at, te.module_key
          """,
      nativeQuery = true)
  List<TenantEntitlementView> findAllViews();
}
