package id.co.nativeapp.restaurant.payment.repository;

import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.projection.PaymentReceiptView;
import id.co.nativeapp.restaurant.payment.projection.PaymentView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@code payment} aggregate. Writes use the inherited {@code
 * saveAndFlush} (the full aggregate on the write path); reads use native-query projections (only
 * the needed columns, CODE-STRUCTURE §3.3).
 *
 * <p>All queries are RLS-scoped via the tenant GUC set by {@code RlsAutoApplyAspect} — no explicit
 * {@code WHERE company_id} is needed in service code (rule 5).
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  /**
   * Fetches the read-path view for a single payment by id. RLS applies — the caller must be in a
   * {@code @Transactional} context with the tenant GUC set.
   *
   * @param id the payment id
   * @return the projection, or empty if not found (or not visible under the current tenant's RLS)
   */
  @Query(
      value =
          "SELECT p.id, p.order_id, p.business_id, p.tender_type, p.status,"
              + " p.amount_minor, p.currency, p.tendered_minor, p.change_minor,"
              + " p.refunded_minor, p.provider_ref, p.provider_pending, p.sale_id,"
              + " p.captured_at, p.occurred_at, p.idempotency_key"
              + " FROM payment p WHERE p.id = :id",
      nativeQuery = true)
  Optional<PaymentView> findViewById(@Param("id") UUID id);

  /**
   * The most recent payment against an order — the order read path's receipt-rebuild need ({@code
   * GET /api/v1/orders/{id}}, {@code OrderWriter.findById}). A voided+retried order can have
   * several payment rows; the newest ({@code created_at DESC}) is the one whose receipt the cashier
   * expects. RLS applies — the caller must be in a {@code @Transactional} context with the tenant
   * GUC set.
   *
   * @param orderId the order to find the latest payment for
   * @return the narrow receipt projection, or empty when the order has no payment (e.g. a parked,
   *     unpaid order)
   */
  @Query(
      value =
          "SELECT p.id AS id, p.order_id AS order_id, p.tender_type AS tender_type,"
              + " p.status AS status, p.amount_minor AS amount_minor, p.currency AS currency,"
              + " p.tendered_minor AS tendered_minor, p.change_minor AS change_minor,"
              + " p.provider_pending AS provider_pending, p.sale_id AS sale_id"
              + " FROM payment p"
              + " WHERE p.order_id = :orderId"
              + " ORDER BY p.created_at DESC"
              + " LIMIT 1",
      nativeQuery = true)
  Optional<PaymentReceiptView> findLatestReceiptViewByOrderId(@Param("orderId") UUID orderId);
}
