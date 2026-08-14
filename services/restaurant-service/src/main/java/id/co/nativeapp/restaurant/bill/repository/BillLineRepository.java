package id.co.nativeapp.restaurant.bill.repository;

import id.co.nativeapp.restaurant.bill.domain.BillLine;
import id.co.nativeapp.restaurant.bill.projection.BillLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BillLine}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies the tenant GUC
 * automatically (rule 5). Read paths return a narrow projection, never {@code SELECT *}.
 */
public interface BillLineRepository extends JpaRepository<BillLine, UUID> {

  /**
   * All lines for a given bill, projected to {@link BillLineView}. RLS-scoped via the session
   * tenant. Results are ordered by creation time so appended rounds appear in encounter order.
   */
  @Query(
      value =
          """
          SELECT bl.id                    AS id,
                 bl.menu_item_id          AS menu_item_id,
                 bl.name_snapshot         AS name_snapshot,
                 bl.unit_price_minor      AS unit_price_minor,
                 bl.modifier_delta_minor  AS modifier_delta_minor,
                 bl.qty                   AS qty,
                 bl.line_total_minor      AS line_total_minor,
                 bl.paid                  AS paid,
                 bl.paid_sale_id          AS paid_sale_id,
                 bl.pending_payment_id    AS pending_payment_id
            FROM bill_line bl
           WHERE bl.bill_id = :billId
           ORDER BY bl.created_at
          """,
      nativeQuery = true)
  List<BillLineView> findViewsByBillId(@Param("billId") UUID billId);

  /**
   * The lines currently RESERVED for one in-flight gateway payment (V38) — {@code
   * BillWriter.initiatePendingPayment} stamps {@code pending_payment_id} on every unpaid line it
   * reserves; {@code BillPaymentCaptureWriter#capture} resolves this set at capture time. RLS
   * applies.
   */
  @Query(
      value =
          """
          SELECT bl.id                    AS id,
                 bl.menu_item_id          AS menu_item_id,
                 bl.name_snapshot         AS name_snapshot,
                 bl.unit_price_minor      AS unit_price_minor,
                 bl.modifier_delta_minor  AS modifier_delta_minor,
                 bl.qty                   AS qty,
                 bl.line_total_minor      AS line_total_minor,
                 bl.paid                  AS paid,
                 bl.paid_sale_id          AS paid_sale_id,
                 bl.pending_payment_id    AS pending_payment_id
            FROM bill_line bl
           WHERE bl.pending_payment_id = CAST(:paymentId AS uuid)
           ORDER BY bl.created_at
          """,
      nativeQuery = true)
  List<BillLineView> findViewsByPendingPaymentId(@Param("paymentId") UUID paymentId);

  /**
   * Bulk-marks a set of bill lines as paid for the CASH/MANUAL/STATIC/ONLINE {@code payBill} path
   * (V38 C1 fix) — guarded {@code AND paid = false AND pending_payment_id IS NULL} so the UPDATE
   * itself RE-VALIDATES, at write time, that no gateway payment reserved these lines in the window
   * between {@code payBill}'s initial unpaid-line read and this write. Without this guard a
   * concurrent {@link #reserveUnpaidLines} that commits inside that window would be silently
   * clobbered (the line would end up {@code paid = true} AND the customer double-charged: cash here
   * plus whatever the still-live QRIS/CARD payment later captures) — the exact race {@code
   * BillWriter}'s caller MUST treat a short row count as a conflict for.
   *
   * <p>Runs in the caller's transaction (propagation MANDATORY via the {@code @Transactional} on
   * the enclosing {@link id.co.nativeapp.restaurant.bill.service.BillWriter BillWriter} method).
   * RLS is active; only lines visible to the current tenant are updated.
   *
   * @return the number of rows actually updated — the caller MUST compare this against {@code
   *     lineIds.size()} and treat a shortfall as a conflict (roll back the whole check — the sale
   *     and outbox row it already wrote in the SAME transaction roll back too)
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE bill_line
             SET paid                = true,
                 paid_sale_id        = CAST(:saleId AS uuid),
                 pending_payment_id  = NULL,
                 updated_at          = NOW(),
                 updated_by          = :updatedBy,
                 version             = version + 1
           WHERE id IN (:lineIds)
             AND paid = false
             AND pending_payment_id IS NULL
          """,
      nativeQuery = true)
  int markLinesPaidForCash(
      @Param("lineIds") List<UUID> lineIds,
      @Param("saleId") UUID saleId,
      @Param("updatedBy") String updatedBy);

  /**
   * Bulk-marks a set of bill lines as paid for the GATEWAY CAPTURE path (V38 C1 fix) — guarded
   * {@code AND pending_payment_id = :paymentId} so the UPDATE only ever touches lines still held by
   * THIS SPECIFIC payment's reservation, re-validated at write time (not just at the {@code
   * findViewsByPendingPaymentId} read moments earlier). Without this guard a stale/duplicate
   * capture (or one racing a concurrent {@code abandon}) could blindly re-mark a line some OTHER
   * payment (or a cash check) has since claimed, corrupting {@code paid_sale_id} and
   * double-counting revenue — the mirror of {@link #markLinesPaidForCash}'s guard on the cash side.
   *
   * <p>Runs in the caller's transaction (propagation REQUIRES_NEW on the enclosing {@code
   * BillPaymentCaptureWriter#capture}). RLS is active; only lines visible to the current tenant are
   * updated.
   *
   * @return the number of rows actually updated — the caller compares this against {@code
   *     lineIds.size()} and treats a shortfall as a conflict
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE bill_line
             SET paid                = true,
                 paid_sale_id        = CAST(:saleId AS uuid),
                 pending_payment_id  = NULL,
                 updated_at          = NOW(),
                 updated_by          = :updatedBy,
                 version             = version + 1
           WHERE id IN (:lineIds)
             AND pending_payment_id = CAST(:paymentId AS uuid)
          """,
      nativeQuery = true)
  int markLinesPaidForCapture(
      @Param("lineIds") List<UUID> lineIds,
      @Param("paymentId") UUID paymentId,
      @Param("saleId") UUID saleId,
      @Param("updatedBy") String updatedBy);

  /**
   * RESERVES every still-unpaid, not-yet-reserved line on {@code billId} against {@code paymentId}
   * (V38) — {@code BillWriter.initiatePendingPayment}'s atomic claim. A concurrent cash {@code
   * payBill} against the SAME lines finds nothing to pay (its own unpaid-line read excludes any row
   * with a non-null {@code pending_payment_id} the instant this UPDATE commits — READ COMMITTED
   * sees the committed reservation).
   *
   * @return the number of rows reserved — the caller compares this against the unpaid-line count it
   *     read moments earlier and throws {@link
   *     id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException} on a mismatch
   *     (a concurrent racer claimed one or more lines first)
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE bill_line
             SET pending_payment_id  = CAST(:paymentId AS uuid),
                 updated_at          = NOW(),
                 updated_by          = :updatedBy,
                 version             = version + 1
           WHERE bill_id = CAST(:billId AS uuid)
             AND paid = false
             AND pending_payment_id IS NULL
          """,
      nativeQuery = true)
  int reserveUnpaidLines(
      @Param("billId") UUID billId,
      @Param("paymentId") UUID paymentId,
      @Param("updatedBy") String updatedBy);

  /**
   * Releases the reservation on every line carrying {@code paymentId} (V38) — {@code
   * BillPaymentWriter#abandon} (and {@code BillWriter.initiatePendingPayment}'s self-heal of a
   * stale prior attempt), so a concurrent cash {@code payBill} can claim the lines again.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE bill_line
             SET pending_payment_id  = NULL,
                 updated_at          = NOW(),
                 updated_by          = :updatedBy,
                 version             = version + 1
           WHERE pending_payment_id = CAST(:paymentId AS uuid)
          """,
      nativeQuery = true)
  int releaseReservation(@Param("paymentId") UUID paymentId, @Param("updatedBy") String updatedBy);
}
