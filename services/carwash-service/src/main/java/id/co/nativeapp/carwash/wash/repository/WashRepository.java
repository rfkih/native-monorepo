package id.co.nativeapp.carwash.wash.repository;

import id.co.nativeapp.carwash.wash.domain.Wash;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Wash}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...} and no call to apply
 * the tenant GUC: every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets
 * {@code app.current_tenant} on the connection automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (correctness by default — rule 5). The {@code
 * idempotency_key} lookup is therefore implicitly tenant-scoped: it can only ever find a row
 * belonging to the session tenant, which matches the {@code (company_id, idempotency_key)} unique
 * constraint exactly.
 */
public interface WashRepository extends JpaRepository<Wash, UUID> {

  /**
   * Finds an existing wash by its client idempotency key within the bound tenant (RLS-scoped). Used
   * to make record-wash idempotent on retry.
   */
  Optional<Wash> findByIdempotencyKey(String idempotencyKey);
}
