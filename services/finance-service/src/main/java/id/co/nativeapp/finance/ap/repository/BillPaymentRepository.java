package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.BillPayment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for {@link BillPayment} rows. Inherited {@code save} is the write path; the native
 * lookup below backs the payment-idempotency replay (mirroring AR's code-review C1). RLS-scoped.
 */
public interface BillPaymentRepository extends JpaRepository<BillPayment, UUID> {

  /**
   * The id of an existing payment on {@code billId} carrying {@code key} in the bound tenant, if
   * any (RLS-scoped) — the idempotency replay lookup, scoped to the bill so a key reused on a
   * different bill is NOT mistaken for a replay (mirroring AR's code-review C-1).
   */
  @Query(
      value = "SELECT id FROM bill_payment WHERE bill_id = :billId AND idempotency_key = :key",
      nativeQuery = true)
  Optional<UUID> findIdByBillIdAndIdempotencyKey(UUID billId, String key);
}
