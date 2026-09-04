package id.co.nativeapp.restaurant.bill.repository;

import id.co.nativeapp.restaurant.bill.domain.Bill;
import id.co.nativeapp.restaurant.bill.projection.BillSummaryView;
import id.co.nativeapp.restaurant.bill.projection.BillView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Bill}.
 *
 * <p>Carries no manual {@code WHERE company_id} and no RLS synchronizer calls: every method runs
 * inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code app.current_tenant}
 * automatically (rule 5). Read paths use a native query + projection (never {@code SELECT *}).
 */
public interface BillRepository extends JpaRepository<Bill, UUID> {

  /**
   * Loads the bill FOR UPDATE — the serialization point of every bill write path (2026-08-31 audit
   * C1/H1 fix). {@code Bill.cancel()}'s paid/reserved-line guard is a read of child rows, and a
   * PARTIAL split-pay / gateway reservation mutates {@code bill_line} via native UPDATEs WITHOUT
   * dirtying the parent {@code bill} row — so optimistic {@code @Version} alone cannot serialize a
   * cancel against them (TOCTOU: cancel's line snapshot goes stale, its version check still passes,
   * and a recorded sale or live PSP reservation is stranded on a CANCELLED bill). Every mutating
   * path that either changes line paid/reserved state or judges it (cancelBill, payBill,
   * initiatePendingPayment, BillPaymentCaptureWriter.capture) MUST load the bill through this
   * finder, making the bill row the common lock; the canonical lock order is bill → bill_line →
   * payment (see BillPaymentWriter#doAbandon for the payment-side ordering).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Bill> findWithLockById(UUID id);

  /**
   * Fetches a single bill's header by id (RLS-scoped). Used for the write path after a {@code
   * findById} to get a projection quickly; the write path uses {@link #findById} for the full
   * aggregate.
   */
  @Query(
      value =
          """
          SELECT b.id              AS id,
                 b.business_id     AS business_id,
                 b.table_id        AS table_id,
                 b.guest_label     AS guest_label,
                 b.status          AS status,
                 b.currency        AS currency,
                 b.discount_minor  AS discount_minor,
                 b.sale_id         AS sale_id
            FROM bill b
           WHERE b.id = :billId
          """,
      nativeQuery = true)
  Optional<BillView> findViewById(@Param("billId") UUID billId);

  /**
   * Lists bills for a business, optionally filtered by status. Used for the GET list endpoint.
   * Returns a summary with the running total (aggregated from bill_line) and line count.
   *
   * <p>When {@code tableId} is not null the query also filters by table_id — passed via a second
   * query to avoid dynamic SQL in a native query (see {@link
   * #findSummaryByBusinessIdAndStatusAndTable}).
   */
  @Query(
      value =
          """
          SELECT b.id                              AS id,
                 b.business_id                     AS business_id,
                 b.table_id                        AS table_id,
                 b.guest_label                     AS guest_label,
                 b.status                          AS status,
                 b.currency                        AS currency,
                 b.discount_minor                  AS discount_minor,
                 COALESCE(SUM(l.line_total_minor), 0) AS running_total_minor,
                 COUNT(l.id)                       AS line_count
            FROM bill b
            LEFT JOIN bill_line l ON l.bill_id = b.id
           WHERE b.business_id = :businessId
             AND b.status = :status
           GROUP BY b.id, b.business_id, b.table_id, b.guest_label,
                    b.status, b.currency, b.discount_minor
           ORDER BY b.created_at DESC
          """,
      nativeQuery = true)
  List<BillSummaryView> findSummaryByBusinessIdAndStatus(
      @Param("businessId") UUID businessId, @Param("status") String status);

  /**
   * Lists bills for a business + status filtered by table_id. Used when the caller passes a {@code
   * tableId} query param.
   */
  @Query(
      value =
          """
          SELECT b.id                              AS id,
                 b.business_id                     AS business_id,
                 b.table_id                        AS table_id,
                 b.guest_label                     AS guest_label,
                 b.status                          AS status,
                 b.currency                        AS currency,
                 b.discount_minor                  AS discount_minor,
                 COALESCE(SUM(l.line_total_minor), 0) AS running_total_minor,
                 COUNT(l.id)                       AS line_count
            FROM bill b
            LEFT JOIN bill_line l ON l.bill_id = b.id
           WHERE b.business_id = :businessId
             AND b.status = :status
             AND b.table_id = :tableId
           GROUP BY b.id, b.business_id, b.table_id, b.guest_label,
                    b.status, b.currency, b.discount_minor
           ORDER BY b.created_at DESC
          """,
      nativeQuery = true)
  List<BillSummaryView> findSummaryByBusinessIdAndStatusAndTable(
      @Param("businessId") UUID businessId,
      @Param("status") String status,
      @Param("tableId") UUID tableId);
}
