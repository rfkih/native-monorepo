package id.co.nativeapp.restaurant.sale.repository;

import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Sale}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...} and no call to apply
 * the tenant GUC: every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets
 * {@code app.current_tenant} on the connection automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (correctness by default — rule 5). The {@code
 * idempotency_key} lookup is therefore implicitly tenant-scoped: it can only ever find a row
 * belonging to the session tenant, which matches the {@code (company_id, idempotency_key)} unique
 * constraint exactly.
 */
public interface SaleRepository extends JpaRepository<Sale, UUID> {

  /**
   * Finds an existing sale by its client idempotency key within the bound tenant (RLS-scoped). Used
   * to make record-sale idempotent on retry.
   */
  Optional<Sale> findByIdempotencyKey(String idempotencyKey);
}
