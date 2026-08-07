package id.co.nativeapp.payment.charge.repository;

import id.co.nativeapp.payment.charge.domain.ChargeStatus;
import id.co.nativeapp.payment.charge.domain.PaymentCharge;
import id.co.nativeapp.payment.charge.projection.ChargeView;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data port for {@code payment_charge}. The read path (the till's poll) is a native query into
 * {@link ChargeView} (ADR 0002); the write path loads the full aggregate via the derived finders /
 * inherited {@code findById}/{@code save}. Every query is tenant-scoped by RLS (rule 5).
 */
public interface PaymentChargeRepository extends JpaRepository<PaymentCharge, UUID> {

  /** Write path: replay probe by the console's Idempotency-Key. */
  Optional<PaymentCharge> findByIdempotencyKey(String idempotencyKey);

  /** Write path: the payment's live charge, if any (the V3 partial-unique's query twin). */
  Optional<PaymentCharge> findFirstByPaymentIdAndStatusIn(
      UUID paymentId, Collection<ChargeStatus> statuses);

  /** Write path: the webhook's lookup by Midtrans order_id (RLS makes forged tenants see none). */
  Optional<PaymentCharge> findByProviderOrderId(String providerOrderId);

  /** The till's poll view. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT id,
                 status,
                 vertical,
                 payment_id,
                 qr_string,
                 qr_url,
                 expires_at,
                 amount_minor,
                 currency
            FROM payment_charge
           WHERE id = :id
          """)
  Optional<ChargeView> findViewById(@Param("id") UUID id);
}
