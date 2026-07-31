package id.co.nativeapp.restaurant.selforderaccess.repository;

import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SelfOrderAccess}.
 *
 * <p>A thin data port: derived queries only (no {@code @Query}, so the {@code
 * repositoryQueriesAreNative} rule does not apply), no manual {@code WHERE company_id} — RLS
 * auto-applies (rule 5). Both finders return the FULL entity (never a projection): the caller needs
 * the decrypted {@code secretPlaintext} (only available via the JPA attribute converter on a
 * hydrated entity), exactly the same allowance {@code entitlement.repository
 * .EntitlementProjectionRepository#findByModuleKey} already relies on.
 */
public interface SelfOrderAccessRepository extends JpaRepository<SelfOrderAccess, UUID> {

  /** The currently ACTIVE row for an outlet (RLS-scoped), if any. At most one ever exists. */
  Optional<SelfOrderAccess> findByOutletIdAndStatus(UUID outletId, String status);

  /**
   * The row for a specific {@code (outletId, kid, status)} — the anonymous verification lookup: a
   * garbage/rotated/cross-outlet {@code kid}, or one whose row is RETIRED, resolves to {@link
   * Optional#empty()}.
   */
  Optional<SelfOrderAccess> findByOutletIdAndKidAndStatus(UUID outletId, String kid, String status);
}
