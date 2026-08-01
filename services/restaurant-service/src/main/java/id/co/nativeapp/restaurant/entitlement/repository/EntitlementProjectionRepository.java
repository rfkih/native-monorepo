package id.co.nativeapp.restaurant.entitlement.repository;

import id.co.nativeapp.restaurant.entitlement.domain.EntitlementProjection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link EntitlementProjection} local entitlement read model.
 *
 * <p>A thin data port: the read is a derived query, no business logic, no manual {@code WHERE
 * company_id} — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). The gate looks
 * a module up by key <em>within</em> the bound tenant to learn whether the company is entitled; RLS
 * makes a cross-tenant projection row invisible (empty), so a company can never be entitled via
 * another tenant's row.
 */
public interface EntitlementProjectionRepository
    extends JpaRepository<EntitlementProjection, UUID> {

  /** The projection row for a module within the bound tenant (RLS-scoped), if any. */
  Optional<EntitlementProjection> findByModuleKey(String moduleKey);

  /**
   * Set-if-newer upsert of a consumed {@code EntitlementGranted}/{@code EntitlementRevoked} (bug-
   * audit FIX 3, V20): a fresh row is inserted for a never-before-seen (company, module); an
   * existing row is updated ONLY when the incoming {@code eventOccurredAt} is not older than the
   * stored one — an atomic {@code INSERT ... ON CONFLICT ... DO UPDATE ... WHERE} so a Revoked that
   * raced ahead of a lagging/redelivered, chronologically-earlier Granted (the two arrive on
   * separate topics with independent lag; there is no ordering guarantee between them) can never be
   * clobbered back to entitled=true. Mirrors {@code
   * loyaltyref.repository.MemberBalanceRefRepository#upsertSetIfNewer}'s {@code balance_seq} guard,
   * using the timestamp both entitlement events already carry ({@code granted_at}/{@code
   * revoked_at}) in place of a dedicated sequence number.
   *
   * @param id a freshly-minted id, used only on the INSERT branch (a conflict keeps the existing
   *     row's id — the conflict target is the tenant-composite unique constraint, not the PK)
   * @param actor stamped into {@code created_by}/{@code updated_by} — the consumer's fixed system
   *     actor ({@code EntitlementProjectionService#CONSUMER_ACTOR})
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO entitlement_projection
            (id, module_key, entitled, event_occurred_at,
             created_at, created_by, updated_at, updated_by, version, company_id)
          VALUES (:id, :moduleKey, :entitled, :eventOccurredAt,
                  NOW(), :actor, NOW(), :actor, 0, :companyId)
          ON CONFLICT ON CONSTRAINT uq_entitlement_projection_company_module DO UPDATE SET
            entitled          = EXCLUDED.entitled,
            event_occurred_at = EXCLUDED.event_occurred_at,
            updated_at        = EXCLUDED.updated_at,
            updated_by        = EXCLUDED.updated_by,
            version           = entitlement_projection.version + 1
          WHERE entitlement_projection.event_occurred_at <= EXCLUDED.event_occurred_at
          """,
      nativeQuery = true)
  int upsertSetIfNewer(
      @Param("id") UUID id,
      @Param("moduleKey") String moduleKey,
      @Param("entitled") boolean entitled,
      @Param("eventOccurredAt") Instant eventOccurredAt,
      @Param("actor") String actor,
      @Param("companyId") String companyId);
}
