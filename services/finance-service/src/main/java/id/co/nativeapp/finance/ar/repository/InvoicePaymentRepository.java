package id.co.nativeapp.finance.ar.repository;

import id.co.nativeapp.finance.ar.domain.InvoicePayment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for {@link InvoicePayment} rows. Inherited {@code save} is the write path; the native
 * lookup below backs the payment-idempotency replay (code-review C1). RLS-scoped.
 */
public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

  /**
   * The id of an existing payment on {@code invoiceId} carrying {@code key} in the bound tenant, if
   * any (RLS-scoped) — the idempotency replay lookup, scoped to the invoice so a key reused on a
   * different invoice is NOT mistaken for a replay (code-review C-1).
   */
  @Query(
      value =
          "SELECT id FROM invoice_payment WHERE invoice_id = :invoiceId AND idempotency_key = :key",
      nativeQuery = true)
  Optional<UUID> findIdByInvoiceIdAndIdempotencyKey(UUID invoiceId, String key);
}
